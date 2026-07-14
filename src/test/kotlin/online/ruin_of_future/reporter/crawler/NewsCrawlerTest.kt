package online.ruin_of_future.reporter.crawler

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NewsCrawlerTest {
    private val crawler = NewsCrawler()

    @Test
    fun parsesCurrentNewsResponse() {
        val items = crawler.parseNewsResponse(
            """
                {
                  "status": 200,
                  "message": "success",
                  "data": ["1、第一条新闻；", "2、第二条新闻；"],
                  "time": "20260714"
                }
            """.trimIndent(),
            "20260714",
        )

        assertEquals(listOf("1、第一条新闻；", "2、第二条新闻；"), items)
    }

    @Test
    fun rejectsStaleNewsResponse() {
        val error = assertFailsWith<NewsException> {
            crawler.parseNewsResponse(
                """
                    {
                      "status": 200,
                      "message": "success",
                      "data": ["1、过期新闻；"],
                      "time": "20260713"
                    }
                """.trimIndent(),
                "20260714",
            )
        }

        assertTrue(error.message.orEmpty().contains("stale"))
    }

    @Test
    fun rendersNewsAsPng() {
        val bytes = crawler.renderNewsImage(
            listOf(
                "1、第一条用于验证图片排版和中文字体的新闻；",
                "2、这是一条较长的新闻，用于确认内容超过单行宽度时能够自动换行而不被裁切；",
            ),
            "20260714",
        )

        assertTrue(bytes.size > 8)
        assertEquals(listOf(137, 80, 78, 71), bytes.take(4).map(Byte::toUByte).map(UByte::toInt))
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull(image)
        assertEquals(1200, image.width)
        assertTrue(image.height > 300)
    }
}
