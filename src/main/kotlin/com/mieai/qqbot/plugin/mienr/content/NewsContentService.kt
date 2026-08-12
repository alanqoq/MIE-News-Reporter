package com.mieai.qqbot.plugin.mienr.content

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
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

/** Fetches, renders, and persistently caches the current daily-news image. */
class NewsContentService(
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
        CompletableFuture.failedFuture(NewsContentException("Daily-news request could not be scheduled", failure))
    }

    private fun startRequest(dateKey: String): CompletableFuture<ByteArray> =
        CompletableFuture.supplyAsync({ cache.read(dateKey) }, executor)
            .thenCompose { cached ->
                if (cached != null) {
                    CompletableFuture.completedFuture(cached)
                } else {
                    fetchNews(dateKey)
                        .thenCompose { items ->
                            CompletableFuture.supplyAsync({ render(items, dateKey) }, executor)
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

    private fun fetchNews(expectedDate: String): CompletionStage<List<String>> {
        val request = PluginHttpRequest(
            method = "GET",
            uri = endpoint,
            headers = mapOf("Accept" to "application/json"),
            timeout = REQUEST_TIMEOUT,
        )
        val response = try {
            requireNotNull(httpClient.send(request)) { "PluginHttpClient returned no response stage" }
        } catch (failure: Exception) {
            return CompletableFuture.failedFuture(
                NewsContentException("Daily-news request could not be started", failure),
            )
        }
        return response.thenApply { parseResponse(it, expectedDate) }
    }

    internal fun parseResponse(response: PluginHttpResponse, expectedDate: String): List<String> {
        if (response.statusCode !in 200..299) {
            throw NewsContentException("Daily-news request returned HTTP ${response.statusCode}")
        }

        val body = response.body
        if (body.size > MAX_RESPONSE_BYTES) {
            throw NewsContentException("Daily-news response exceeds the 2 MiB limit")
        }

        val root = try {
            JsonParser.parseString(decodeUtf8(body)).asJsonObject
        } catch (failure: Exception) {
            throw NewsContentException("Daily-news response JSON is invalid", failure)
        }

        val status = root.requiredInt("status")
        val message = root.requiredString("message")
        if (status != 200 || !message.equals("success", ignoreCase = true)) {
            throw NewsContentException("Daily-news service returned $status: $message")
        }

        val responseDate = root.requiredString("time").trim()
        if (responseDate != expectedDate) {
            throw NewsContentException("Daily news is stale: expected $expectedDate, received $responseDate")
        }

        val data = root.get("data")
        if (data == null || !data.isJsonArray) {
            throw NewsContentException("Daily-news response data must be an array")
        }
        if (data.asJsonArray.size() !in 1..MAX_NEWS_ITEMS) {
            throw NewsContentException("Daily-news response contains an invalid number of items")
        }

        val items = data.asJsonArray.map { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                throw NewsContentException("Daily-news items must be strings")
            }
            element.asString.trim().also {
                if (it.isEmpty()) throw NewsContentException("Daily-news items must not be blank")
            }
        }
        if (items.sumOf(String::length) > MAX_NEWS_CHARACTERS) {
            throw NewsContentException("Daily-news response contains too much text")
        }
        return items
    }

    internal fun render(newsItems: List<String>, dateKey: String): ByteArray {
        val titleFont = font.deriveFont(Font.BOLD, 54f)
        val dateFont = font.deriveFont(Font.PLAIN, 28f)
        val itemFont = font.deriveFont(Font.PLAIN, 32f)
        val footerFont = font.deriveFont(Font.PLAIN, 24f)

        val measurementImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val (itemMetrics, wrappedItems) = measurementImage.createGraphics().useGraphics { graphics ->
            graphics.font = itemFont
            val metrics = graphics.fontMetrics
            metrics to newsItems.map { wrapText(it, metrics, CONTENT_WIDTH) }
        }

        val lineHeight = (itemMetrics.height * 1.2).toInt()
        val itemsHeight = wrappedItems.sumOf { it.size * lineHeight + ITEM_GAP }
        val imageHeight = HEADER_HEIGHT + CONTENT_TOP_PADDING + itemsHeight + FOOTER_HEIGHT
        if (imageHeight > MAX_IMAGE_HEIGHT) {
            throw ContentRenderException("Rendered daily-news image is too tall")
        }

        val image = BufferedImage(IMAGE_WIDTH, imageHeight, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().useGraphics { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            graphics.color = Color(0xF7F8FA)
            graphics.fillRect(0, 0, image.width, image.height)

            graphics.color = Color(0xD6, 0x56, 0x9B)
            graphics.fillRect(0, 0, image.width, HEADER_HEIGHT)
            graphics.color = Color.WHITE
            graphics.font = titleFont
            graphics.drawString("60秒读懂世界", HORIZONTAL_PADDING, 82)
            graphics.font = dateFont
            graphics.drawString(displayDate(dateKey), HORIZONTAL_PADDING, 132)

            var baseline = HEADER_HEIGHT + CONTENT_TOP_PADDING + itemMetrics.ascent
            wrappedItems.forEachIndexed { index, lines ->
                graphics.color = Color(0x22, 0x26, 0x2D)
                graphics.font = itemFont
                lines.forEach { line ->
                    graphics.drawString(line, HORIZONTAL_PADDING, baseline)
                    baseline += lineHeight
                }

                if (index < wrappedItems.lastIndex) {
                    graphics.color = Color(0xE2, 0xE5, 0xE9)
                    graphics.drawLine(
                        HORIZONTAL_PADDING,
                        baseline + ITEM_GAP / 2,
                        IMAGE_WIDTH - HORIZONTAL_PADDING,
                        baseline + ITEM_GAP / 2,
                    )
                }
                baseline += ITEM_GAP
            }

            graphics.color = Color(0x76, 0x7B, 0x84)
            graphics.font = footerFont
            graphics.drawString("数据来源：LyToday", HORIZONTAL_PADDING, imageHeight - 36)
        }

        return ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(image, "png", output)) {
                throw ContentRenderException("PNG image writer is unavailable")
            }
            output.toByteArray()
        }
    }

    private fun wrapText(text: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        text.lines().forEach { paragraph ->
            if (paragraph.isEmpty()) {
                lines += ""
            } else {
                val line = StringBuilder()
                paragraph.codePoints().forEach { codePoint ->
                    val character = String(Character.toChars(codePoint))
                    if (line.isNotEmpty() && metrics.stringWidth(line.toString() + character) > maxWidth) {
                        lines += line.toString()
                        line.clear()
                    }
                    line.append(character)
                }
                if (line.isNotEmpty()) lines += line.toString()
            }
        }
        return lines.ifEmpty { listOf("") }
    }

    private fun displayDate(dateKey: String): String = try {
        LocalDate.parse(dateKey, DATE_FORMAT).format(DISPLAY_DATE_FORMAT)
    } catch (failure: Exception) {
        throw ContentRenderException("Invalid daily-news date: $dateKey", failure)
    }

    private data class DatedRequest(
        val dateKey: String,
        val future: CompletableFuture<ByteArray>,
    )

    companion object {
        @JvmField
        val DEFAULT_ENDPOINT: URI = URI.create("https://cdn.lylme.com/api/60s/")

        private const val CACHE_PREFIX = "news"
        private const val IMAGE_WIDTH = 1200
        private const val HORIZONTAL_PADDING = 72
        private const val CONTENT_WIDTH = IMAGE_WIDTH - HORIZONTAL_PADDING * 2
        private const val HEADER_HEIGHT = 170
        private const val CONTENT_TOP_PADDING = 48
        private const val ITEM_GAP = 20
        private const val FOOTER_HEIGHT = 88
        private const val MAX_IMAGE_HEIGHT = 10_000
        private const val MAX_NEWS_ITEMS = 50
        private const val MAX_NEWS_CHARACTERS = 20_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val DISPLAY_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

class NewsContentException(message: String, cause: Throwable? = null) : ContentServiceException(message, cause)

private fun JsonObject.requiredString(name: String): String {
    val element = get(name)
    if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw NewsContentException("Daily-news response field '$name' must be a string")
    }
    return element.asString
}

private fun JsonObject.requiredInt(name: String): Int {
    val element = get(name)
    if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw NewsContentException("Daily-news response field '$name' must be a number")
    }
    return try {
        element.asBigDecimal.intValueExact()
    } catch (failure: RuntimeException) {
        throw NewsContentException("Daily-news response field '$name' must be an integer", failure)
    }
}

private fun decodeUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()

private inline fun <T : java.awt.Graphics2D, R> T.useGraphics(block: (T) -> R): R = try {
    block(this)
} finally {
    dispose()
}
