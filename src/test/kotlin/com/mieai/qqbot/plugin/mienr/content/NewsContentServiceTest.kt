package com.mieai.qqbot.plugin.mienr.content

import com.mieai.qqbot.plugin.api.PluginHttpResponse
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NewsContentServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun fetchesRendersAndPersistsOnlyCurrentNewsImage() {
        val cacheDirectory = Files.createDirectories(temporaryDirectory.resolve("cache"))
        val oldCache = cacheDirectory.resolve("news-20260713.png")
        val corruptCurrentCache = cacheDirectory.resolve("news-20260714.png")
        val unrelated = cacheDirectory.resolve("notes.txt")
        Files.writeString(oldCache, "old")
        Files.write(corruptCurrentCache, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        Files.writeString(unrelated, "keep")

        val endpoint = URI.create("https://news.example.test/today")
        val http = RecordingHttpClient.completed { request ->
            assertEquals(endpoint, request.uri)
            assertEquals("GET", request.method)
            jsonResponse(NEWS_JSON)
        }
        val service = NewsContentService(
            dataDirectory = cacheDirectory,
            zoneId = TEST_ZONE,
            clock = TEST_CLOCK,
            httpClient = http,
            fontResource = bundledTestFont(),
            endpoint = endpoint,
        )

        val first = service.todayImage().await()
        val image = decodePng(first)
        assertEquals(1200, image.width)
        assertTrue(image.height > 300)
        assertEquals(1, http.requests().size)
        assertTrue(Files.isRegularFile(cacheDirectory.resolve("news-20260714.png")))
        assertFalse(Files.exists(oldCache))
        assertTrue(Files.exists(unrelated))

        first[0] = 0
        val second = service.todayImage().await()
        assertEquals(0x89, second[0].toUByte().toInt())
        assertEquals(1, http.requests().size)

        val unusedHttp = RecordingHttpClient {
            CompletableFuture.failedFuture(AssertionError("persisted cache should avoid HTTP"))
        }
        val reloaded = NewsContentService(
            dataDirectory = cacheDirectory,
            zoneId = TEST_ZONE,
            clock = TEST_CLOCK,
            httpClient = unusedHttp,
            fontResource = bundledTestFont(),
            endpoint = endpoint,
        )
        assertContentEquals(second, reloaded.todayImage().await())
        assertTrue(unusedHttp.requests().isEmpty())
    }

    @Test
    fun rejectsStaleNewsWithoutWritingCache() {
        val endpoint = URI.create("https://news.example.test/today")
        val oldCache = temporaryDirectory.resolve("news-20260713.png")
        Files.write(oldCache, fixturePng())
        val http = RecordingHttpClient.completed {
            jsonResponse(NEWS_JSON.replace("20260714", "20260713"))
        }
        val service = NewsContentService(
            dataDirectory = temporaryDirectory,
            zoneId = TEST_ZONE,
            clock = TEST_CLOCK,
            httpClient = http,
            fontResource = bundledTestFont(),
            endpoint = endpoint,
        )

        val failure = assertFailsWith<ExecutionException> { service.todayImage().toCompletableFuture().get() }
        assertIs<NewsContentException>(failure.deepestCause())
        assertTrue(failure.deepestCause().message.orEmpty().contains("stale"))
        assertFalse(Files.exists(oldCache))
        assertFalse(Files.exists(temporaryDirectory.resolve("news-20260714.png")))
    }

    @Test
    fun rejectsNonSuccessfulHttpResponse() {
        val http = RecordingHttpClient.completed { PluginHttpResponse(503, emptyMap(), byteArrayOf()) }
        val service = NewsContentService(
            dataDirectory = temporaryDirectory,
            zoneId = TEST_ZONE,
            clock = TEST_CLOCK,
            httpClient = http,
            fontResource = bundledTestFont(),
            endpoint = URI.create("https://news.example.test/today"),
        )

        val failure = assertFailsWith<ExecutionException> { service.todayImage().toCompletableFuture().get() }
        assertTrue(failure.deepestCause().message.orEmpty().contains("HTTP 503"))
    }

    private companion object {
        val NEWS_JSON =
            """
            {
              "status": 200,
              "message": "success",
              "time": "20260714",
              "data": [
                "1、第一条用于验证中文字体和图片缓存的新闻；",
                "2、第二条较长新闻用于确认超出单行宽度时会自动换行。"
              ]
            }
            """.trimIndent()
    }
}
