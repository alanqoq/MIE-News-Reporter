package com.mieai.qqbot.plugin.mienr.content

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Fetches, renders, and persistently caches the current anime-schedule image. */
class AnimeContentService(
    dataDirectory: Path,
    private val zoneId: ZoneId,
    private val clock: Clock,
    private val httpClient: PluginHttpClient,
    private val fontResource: FontResource,
    private val endpoint: URI = DEFAULT_ENDPOINT,
    private val executor: Executor = ForkJoinPool.commonPool(),
) {
    private val cache = DailyPngCache(dataDirectory, CACHE_PREFIX)
    private val requestLock = Any()

    @Volatile
    private var currentRequest: DatedRequest? = null

    private val font: Font by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            fontResource.open().use { Font.createFont(Font.TRUETYPE_FONT, it) }
        } catch (failure: ContentServiceException) {
            throw failure
        } catch (failure: Exception) {
            throw ContentRenderException("Bundled font could not be loaded", failure)
        }
    }

    /** Returns today's PNG without blocking the caller while HTTP, rendering, or disk I/O runs. */
    fun todayImage(): CompletionStage<ByteArray> {
        val dateKey = todayKey()
        val shared = synchronized(requestLock) {
            currentRequest?.takeIf { it.dateKey == dateKey }?.future ?: startRequestSafely(dateKey).also { future ->
                currentRequest = DatedRequest(dateKey, future)
                future.whenComplete { _, failure ->
                    if (failure != null) {
                        synchronized(requestLock) {
                            if (currentRequest?.future === future) currentRequest = null
                        }
                    }
                }
            }
        }

        return shared.thenApply(ByteArray::clone)
    }

    /** Deletes this service's PNG files from previous calendar days without fetching new data. */
    fun cleanupExpiredImages() {
        cache.deleteOld(todayKey())
    }

    private fun todayKey(): String = LocalDate.now(clock.withZone(zoneId)).format(DATE_FORMAT)

    private fun startRequestSafely(dateKey: String): CompletableFuture<ByteArray> = try {
        startRequest(dateKey)
    } catch (failure: Exception) {
        CompletableFuture.failedFuture(AnimeContentException("Anime request could not be scheduled", failure))
    }

    private fun startRequest(dateKey: String): CompletableFuture<ByteArray> =
        CompletableFuture.supplyAsync({ cache.read(dateKey) }, executor)
            .thenCompose { cached ->
                if (cached != null) {
                    CompletableFuture.completedFuture(cached)
                } else {
                    fetchSchedule(dateKey)
                        .thenCompose(::fetchCovers)
                        .thenCompose { entries ->
                            CompletableFuture.supplyAsync({ render(entries) }, executor)
                        }
                        .thenApplyAsync({ bytes ->
                            persistIfCurrentDay(dateKey, bytes)
                            bytes
                        }, executor)
                }
            }
            .toCompletableFuture()

    private fun persistIfCurrentDay(dateKey: String, bytes: ByteArray) {
        val currentDateKey = todayKey()
        if (currentDateKey == dateKey) {
            cache.write(dateKey, bytes)
        } else {
            // A request that crossed midnight may still finish; do not resurrect its old PNG.
            cache.deleteOld(currentDateKey)
        }
    }

    private fun fetchSchedule(dateKey: String): CompletionStage<List<AnimeScheduleItem>> {
        val request = PluginHttpRequest(
            method = "GET",
            uri = endpoint,
            headers = mapOf("Accept" to "application/json"),
            timeout = REQUEST_TIMEOUT,
        )
        return send(request, "Anime timeline").thenApply { parseTimeline(it, dateKey) }
    }

    private fun fetchCovers(items: List<AnimeScheduleItem>): CompletionStage<List<RenderedAnimeItem>> {
        val covers = items.map { item ->
            val request = PluginHttpRequest(
                method = "GET",
                uri = item.coverUri,
                headers = mapOf("Accept" to "image/*"),
                timeout = REQUEST_TIMEOUT,
            )
            send(request, "Anime cover")
                .thenApplyAsync({ response -> decodeCover(response, item.coverUri) }, executor)
                .toCompletableFuture()
        }

        return CompletableFuture.allOf(*covers.toTypedArray()).thenApply {
            items.mapIndexed { index, item -> RenderedAnimeItem(item, covers[index].join()) }
        }
    }

    private fun send(request: PluginHttpRequest, description: String): CompletionStage<PluginHttpResponse> = try {
        requireNotNull(httpClient.send(request)) { "PluginHttpClient returned no response stage" }
    } catch (failure: Exception) {
        CompletableFuture.failedFuture(
            AnimeContentException("$description request could not be started", failure),
        )
    }

    internal fun parseTimeline(response: PluginHttpResponse, dateKey: String): List<AnimeScheduleItem> {
        if (response.statusCode !in 200..299) {
            throw AnimeContentException("Anime timeline request returned HTTP ${response.statusCode}")
        }

        val body = response.body
        if (body.size > MAX_TIMELINE_BYTES) {
            throw AnimeContentException("Anime timeline response exceeds the 4 MiB limit")
        }

        val root = try {
            JsonParser.parseString(decodeAnimeUtf8(body)).asJsonObject
        } catch (failure: Exception) {
            throw AnimeContentException("Anime timeline response JSON is invalid", failure)
        }

        val code = root.requiredAnimeInt("code")
        if (code != 0) {
            val message = root.optionalAnimeString("message").orEmpty()
            throw AnimeContentException("Anime timeline service returned $code: $message")
        }

        val result = root.get("result")
        if (result == null || !result.isJsonArray) {
            throw AnimeContentException("Anime timeline result must be an array")
        }

        val expectedDates = try {
            LocalDate.parse(dateKey, DATE_FORMAT).let { date ->
                setOf(
                    "${date.monthValue}-${date.dayOfMonth}",
                    date.format(PADDED_API_DATE_FORMAT),
                )
            }
        } catch (failure: Exception) {
            throw AnimeContentException("Invalid anime date: $dateKey", failure)
        }

        val day = result.asJsonArray.firstOrNull { element ->
            element.isJsonObject && element.asJsonObject.optionalAnimeString("date") in expectedDates
        }?.asJsonObject ?: throw NoAnimeContentException("No anime schedule found for ${expectedDates.first()}")

        val seasons = day.get("seasons")
        if (seasons == null || !seasons.isJsonArray) {
            throw AnimeContentException("Anime timeline seasons must be an array")
        }

        val items = seasons.asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) {
                throw AnimeContentException("Anime timeline entries must be objects")
            }
            val item = element.asJsonObject
            if (item.requiredAnimeInt("delay") != 0) return@mapNotNull null

            val title = item.requiredAnimeString("title").trim()
            val publicationTime = item.requiredAnimeString("pub_time").trim()
            if (title.isEmpty() || title.length > MAX_TITLE_CHARACTERS) {
                throw AnimeContentException("Anime title is blank or too long")
            }
            if (publicationTime.isEmpty() || publicationTime.length > MAX_TIME_CHARACTERS) {
                throw AnimeContentException("Anime publication time is blank or too long")
            }

            AnimeScheduleItem(
                title = title,
                publicationTime = publicationTime,
                coverUri = parseCoverUri(
                    item.optionalAnimeString("cover")
                        ?: item.requiredAnimeString("square_cover"),
                ),
            )
        }

        if (items.isEmpty()) throw NoAnimeContentException("No undelayed anime found for ${expectedDates.first()}")
        if (items.size > MAX_ANIME_ITEMS) {
            throw AnimeContentException("Anime timeline contains too many entries")
        }
        return items
    }

    private fun decodeCover(response: PluginHttpResponse, uri: URI): BufferedImage {
        if (response.statusCode !in 200..299) {
            throw AnimeContentException("Anime cover request returned HTTP ${response.statusCode}")
        }

        val body = response.body
        if (body.isEmpty() || body.size > MAX_COVER_BYTES) {
            throw AnimeContentException("Anime cover has an invalid size")
        }

        val image = try {
            val imageInput = ImageIO.createImageInputStream(ByteArrayInputStream(body))
                ?: throw AnimeContentException("Anime cover is not a supported image: ${uri.host.orEmpty()}")
            imageInput.use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) {
                    throw AnimeContentException("Anime cover is not a supported image: ${uri.host.orEmpty()}")
                }
                val reader = readers.next()
                try {
                    reader.input = input
                    validateCoverDimensions(reader.getWidth(0), reader.getHeight(0))
                    reader.read(0)
                } finally {
                    reader.dispose()
                }
            }
        } catch (failure: AnimeContentException) {
            throw failure
        } catch (failure: Exception) {
            throw AnimeContentException("Anime cover could not be decoded: ${uri.host.orEmpty()}", failure)
        }

        validateCoverDimensions(image.width, image.height)
        return image
    }

    private fun validateCoverDimensions(width: Int, height: Int) {
        val pixelCount = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || pixelCount > MAX_COVER_PIXELS) {
            throw AnimeContentException("Anime cover dimensions are invalid")
        }
    }

    internal fun render(items: List<RenderedAnimeItem>): ByteArray {
        if (items.isEmpty()) throw NoAnimeContentException("No anime entries to render")
        val imageHeight = Math.multiplyExact(items.size, ROW_HEIGHT)
        if (imageHeight > MAX_IMAGE_HEIGHT) {
            throw ContentRenderException("Rendered anime image is too tall")
        }

        val image = BufferedImage(IMAGE_WIDTH, imageHeight, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().useAnimeGraphics { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)

            graphics.color = BACKGROUND_COLOR
            graphics.fillRect(0, 0, image.width, image.height)

            items.forEachIndexed { index, entry -> renderRow(graphics, entry, index) }
        }

        return ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(image, "png", output)) {
                throw ContentRenderException("PNG image writer is unavailable")
            }
            output.toByteArray()
        }
    }

    private fun renderRow(graphics: Graphics2D, entry: RenderedAnimeItem, index: Int) {
        val rowY = index * ROW_HEIGHT
        graphics.color = BORDER_COLOR
        graphics.fillRect(0, rowY, IMAGE_WIDTH, ROW_HEIGHT)
        graphics.color = BACKGROUND_COLOR
        graphics.fillRect(SIDE_BORDER, rowY + HORIZONTAL_BORDER, INNER_WIDTH, PREVIEW_HEIGHT)

        val cover = entry.cover
        val naturalWidth = (cover.width.toDouble() * PREVIEW_HEIGHT / cover.height).roundToInt()
        val coverWidth = naturalWidth.coerceIn(MIN_COVER_WIDTH, MAX_COVER_WIDTH)
        val coverX = if (index % 2 == 0) SIDE_BORDER else IMAGE_WIDTH - SIDE_BORDER - coverWidth
        graphics.drawImage(
            cover,
            coverX,
            rowY + HORIZONTAL_BORDER,
            coverWidth,
            PREVIEW_HEIGHT,
            null,
        )

        val textLeft = if (index % 2 == 0) coverX + coverWidth + TEXT_PADDING else SIDE_BORDER + TEXT_PADDING
        val textRight = if (index % 2 == 0) IMAGE_WIDTH - SIDE_BORDER - TEXT_PADDING else coverX - TEXT_PADDING
        val textWidth = textRight - textLeft
        if (textWidth <= 0) throw ContentRenderException("Anime cover leaves no room for title text")

        val titleFont = fitTitleFont(graphics, entry.schedule.title, textWidth)
        graphics.font = titleFont
        graphics.color = TITLE_COLOR
        val titleMetrics = graphics.fontMetrics
        val titleX = textLeft + (textWidth - titleMetrics.stringWidth(entry.schedule.title)) / 2
        val titleBaseline = rowY + HORIZONTAL_BORDER + PREVIEW_HEIGHT / 2
        graphics.drawString(entry.schedule.title, titleX, titleBaseline)

        graphics.font = fitTimeFont(graphics, entry.schedule.publicationTime, textWidth)
        graphics.color = TIME_COLOR
        val timeMetrics = graphics.fontMetrics
        val timeX = textLeft + (textWidth - timeMetrics.stringWidth(entry.schedule.publicationTime)) / 2
        graphics.drawString(entry.schedule.publicationTime, timeX, titleBaseline + TIME_GAP)
    }

    private fun fitTitleFont(graphics: Graphics2D, title: String, maxWidth: Int): Font {
        var size = MAX_TITLE_FONT_SIZE
        while (size > MIN_TITLE_FONT_SIZE) {
            val candidate = font.deriveFont(Font.BOLD, size)
            graphics.font = candidate
            if (graphics.fontMetrics.stringWidth(title) <= maxWidth) return candidate
            size -= 1f
        }

        val smallest = font.deriveFont(Font.BOLD, MIN_TITLE_FONT_SIZE)
        graphics.font = smallest
        if (graphics.fontMetrics.stringWidth(title) > maxWidth) {
            throw ContentRenderException("Anime title is too wide to render")
        }
        return smallest
    }

    private fun fitTimeFont(graphics: Graphics2D, text: String, maxWidth: Int): Font {
        var size = TIME_FONT_SIZE
        while (size > MIN_TIME_FONT_SIZE) {
            val candidate = font.deriveFont(Font.PLAIN, size)
            graphics.font = candidate
            if (graphics.fontMetrics.stringWidth(text) <= maxWidth) return candidate
            size -= 1f
        }

        val smallest = font.deriveFont(Font.PLAIN, MIN_TIME_FONT_SIZE)
        graphics.font = smallest
        if (graphics.fontMetrics.stringWidth(text) > maxWidth) {
            throw ContentRenderException("Anime publication time is too wide to render")
        }
        return smallest
    }

    private fun parseCoverUri(value: String): URI {
        val normalized = value.trim().let { if (it.startsWith("//")) "https:$it" else it }
        val uri = try {
            URI.create(normalized)
        } catch (failure: IllegalArgumentException) {
            throw AnimeContentException("Anime cover URL is invalid", failure)
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw AnimeContentException("Anime cover URL must use HTTP or HTTPS")
        }
        return uri
    }

    private data class DatedRequest(
        val dateKey: String,
        val future: CompletableFuture<ByteArray>,
    )

    companion object {
        @JvmField
        val DEFAULT_ENDPOINT: URI = URI.create("https://bangumi.bilibili.com/web_api/timeline_global")

        private const val CACHE_PREFIX = "anime"
        private const val IMAGE_WIDTH = 1200
        private const val HORIZONTAL_BORDER = 18
        private const val SIDE_BORDER = 20
        private const val PREVIEW_HEIGHT = 600
        private const val ROW_HEIGHT = PREVIEW_HEIGHT + HORIZONTAL_BORDER * 2
        private const val INNER_WIDTH = IMAGE_WIDTH - SIDE_BORDER * 2
        private const val MIN_COVER_WIDTH = 280
        private const val MAX_COVER_WIDTH = 520
        private const val TEXT_PADDING = 24
        private const val MAX_IMAGE_HEIGHT = 20_000
        private const val MAX_ANIME_ITEMS = 24
        private const val MAX_TITLE_CHARACTERS = 200
        private const val MAX_TIME_CHARACTERS = 40
        private const val MAX_TIMELINE_BYTES = 4 * 1024 * 1024
        private const val MAX_COVER_BYTES = 20 * 1024 * 1024
        private const val MAX_COVER_PIXELS = 40_000_000L
        private const val MAX_TITLE_FONT_SIZE = 60f
        private const val MIN_TITLE_FONT_SIZE = 20f
        private const val TIME_FONT_SIZE = 34f
        private const val MIN_TIME_FONT_SIZE = 18f
        private const val TIME_GAP = 58

        private val BACKGROUND_COLOR = Color.decode("#F8EDE3")
        private val BORDER_COLOR = Color.decode("#BDD2B6")
        private val TITLE_COLOR = Color.decode("#4E574C")
        private val TIME_COLOR = Color.decode("#798777")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val PADDED_API_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

/** One validated schedule item before its cover has been fetched. */
internal data class AnimeScheduleItem(
    val title: String,
    val publicationTime: String,
    val coverUri: URI,
)

/** One schedule item and its decoded cover, ready for AWT rendering. */
internal data class RenderedAnimeItem(
    val schedule: AnimeScheduleItem,
    val cover: BufferedImage,
)

open class AnimeContentException(message: String, cause: Throwable? = null) :
    ContentServiceException(message, cause)

class NoAnimeContentException(message: String) : AnimeContentException(message)

private fun JsonObject.requiredAnimeString(name: String): String =
    optionalAnimeString(name)
        ?: throw AnimeContentException("Anime timeline field '$name' must be a string")

private fun JsonObject.optionalAnimeString(name: String): String? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw AnimeContentException("Anime timeline field '$name' must be a string")
    }
    return element.asString
}

private fun JsonObject.requiredAnimeInt(name: String): Int {
    val element = get(name)
    if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw AnimeContentException("Anime timeline field '$name' must be a number")
    }
    return try {
        element.asBigDecimal.intValueExact()
    } catch (failure: RuntimeException) {
        throw AnimeContentException("Anime timeline field '$name' must be an integer", failure)
    }
}

private fun decodeAnimeUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private inline fun <R> Graphics2D.useAnimeGraphics(block: (Graphics2D) -> R): R = try {
    block(this)
} finally {
    dispose()
}
