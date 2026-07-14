package online.ruin_of_future.reporter.crawler

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

@Serializable
internal data class NewsResponse(
    val status: Int,
    val message: String,
    val data: List<String>,
    val time: String,
)

class NewsException(message: String, cause: Throwable? = null) : Exception(message, cause)

private data class NewsImageCache(
    val date: String,
    val bytes: ByteArray,
)

class NewsCrawler(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
) {
    private val httpGetter = HTTPGetter()
    private val entryUrl = "https://cdn.lylme.com/api/60s/"
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheMutex = Mutex()

    @Volatile
    private var imageCache: NewsImageCache? = null

    private val font: Font by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/chinese_font.ttf")) {
            "Missing bundled Chinese font"
        }
        stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }

    fun isCacheValid(): Boolean {
        val cache = imageCache
        return cache != null && cache.date == todayKey() && cache.bytes.isNotEmpty()
    }

    private fun todayKey(): String = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)

    internal fun parseNewsResponse(jsonString: String, expectedDate: String): List<String> {
        val response = try {
            json.decodeFromString<NewsResponse>(jsonString)
        } catch (e: Exception) {
            throw NewsException("Invalid news response", e)
        }

        if (response.status != 200 || !response.message.equals("success", ignoreCase = true)) {
            throw NewsException("News service returned ${response.status}: ${response.message}")
        }
        if (response.time.trim() != expectedDate) {
            throw NewsException("News is stale: expected $expectedDate, received ${response.time}")
        }

        val newsItems = response.data.map(String::trim)
        if (newsItems.isEmpty() || newsItems.any(String::isEmpty)) {
            throw NewsException("News service returned an empty news list")
        }
        if (newsItems.size > MAX_NEWS_ITEMS || newsItems.sumOf(String::length) > MAX_NEWS_CHARACTERS) {
            throw NewsException("News response is too large")
        }
        return newsItems
    }

    private suspend fun getNewsItems(expectedDate: String): List<String> {
        val jsonString = withContext(ioDispatcher) {
            httpGetter.get(entryUrl)
        }
        return parseNewsResponse(jsonString, expectedDate)
    }

    internal fun renderNewsImage(newsItems: List<String>, dateKey: String): ByteArray {
        val titleFont = font.deriveFont(Font.BOLD, 54f)
        val dateFont = font.deriveFont(Font.PLAIN, 28f)
        val itemFont = font.deriveFont(Font.PLAIN, 32f)
        val footerFont = font.deriveFont(Font.PLAIN, 24f)

        val measurementImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val measurementGraphics = measurementImage.createGraphics()
        measurementGraphics.font = itemFont
        val itemMetrics = measurementGraphics.fontMetrics
        val wrappedItems = newsItems.map { wrapText(it, itemMetrics, CONTENT_WIDTH) }
        val lineHeight = (itemMetrics.height * 1.2).toInt()
        measurementGraphics.dispose()

        val itemsHeight = wrappedItems.sumOf { it.size * lineHeight + ITEM_GAP }
        val imageHeight = HEADER_HEIGHT + CONTENT_TOP_PADDING + itemsHeight + FOOTER_HEIGHT
        if (imageHeight > MAX_IMAGE_HEIGHT) {
            throw NewsException("Rendered news image is too tall")
        }

        val image = BufferedImage(IMAGE_WIDTH, imageHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
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
            graphics.drawString(formatDisplayDate(dateKey), HORIZONTAL_PADDING, 132)

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
        } finally {
            graphics.dispose()
        }

        return ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(image, "png", output)) {
                throw NewsException("PNG image writer is unavailable")
            }
            output.toByteArray()
        }
    }

    private fun wrapText(text: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        text.lines().forEach { paragraph ->
            if (paragraph.isEmpty()) {
                lines.add("")
                return@forEach
            }

            val line = StringBuilder()
            paragraph.codePoints().forEach { codePoint ->
                val character = String(Character.toChars(codePoint))
                if (line.isNotEmpty() && metrics.stringWidth(line.toString() + character) > maxWidth) {
                    lines.add(line.toString())
                    line.clear()
                }
                line.append(character)
            }
            if (line.isNotEmpty()) {
                lines.add(line.toString())
            }
        }
        return lines.ifEmpty { listOf("") }
    }

    private fun formatDisplayDate(dateKey: String): String {
        val date = try {
            LocalDate.parse(dateKey, DateTimeFormatter.BASIC_ISO_DATE)
        } catch (e: Exception) {
            throw NewsException("Invalid news date: $dateKey", e)
        }
        return date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }

    suspend fun newsToday(): ByteArray {
        val expectedDate = todayKey()
        imageCache?.takeIf { it.date == expectedDate && it.bytes.isNotEmpty() }?.let { return it.bytes }

        return cacheMutex.withLock {
            imageCache?.takeIf { it.date == expectedDate && it.bytes.isNotEmpty() }?.let {
                return@withLock it.bytes
            }

            val newsItems = getNewsItems(expectedDate)
            val bytes = withContext(Dispatchers.Default) {
                renderNewsImage(newsItems, expectedDate)
            }
            imageCache = NewsImageCache(expectedDate, bytes)
            bytes
        }
    }

    companion object {
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

        val INSTANCE = NewsCrawler()
    }
}
