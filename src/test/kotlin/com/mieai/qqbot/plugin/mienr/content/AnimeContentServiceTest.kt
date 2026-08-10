package com.mieai.qqbot.plugin.mienr.content

import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnimeContentServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun fetchesTimelineAndCoversThenPersistsOnlyCurrentAnimeImage() {
        val cacheDirectory = Files.createDirectories(temporaryDirectory.resolve("cache"))
        val oldCache = cacheDirectory.resolve("anime-20260713.png")
        val otherContentCache = cacheDirectory.resolve("news-20260713.png")
        Files.writeString(oldCache, "old")
        Files.writeString(otherContentCache, "keep")

        val endpoint = URI.create("https://anime.example.test/timeline")
        val firstCover = fixturePng(color = Color(0x4B, 0x78, 0xA8))
        val secondCover = fixturePng(color = Color(0xB0, 0x6D, 0x74))
        val http = RecordingHttpClient.completed { request ->
            when (request.uri) {
                endpoint -> jsonResponse(ANIME_JSON)
                FIRST_COVER -> imageResponse(firstCover)
                SECOND_COVER -> imageResponse(secondCover)
                else -> error("Unexpected request: ${request.uri}")
            }
        }
        val service = AnimeContentService(
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
        assertEquals(1272, image.height)
        assertEquals(listOf(endpoint, FIRST_COVER, SECOND_COVER), http.requests().map { it.uri })
        assertTrue(http.requests().all { it.method == "GET" })
        assertTrue(Files.isRegularFile(cacheDirectory.resolve("anime-20260714.png")))
        assertFalse(Files.exists(oldCache))
        assertTrue(Files.exists(otherContentCache))

        first[0] = 0
        val second = service.todayImage().await()
        assertEquals(0x89, second[0].toUByte().toInt())
        assertEquals(3, http.requests().size)
    }

    @Test
    fun ignoresDelayedEntriesAndFailsWhenNothingRemains() {
        val endpoint = URI.create("https://anime.example.test/timeline")
        val onlyDelayed = ANIME_JSON.replace(
            SECOND_ENTRY,
            """{"delay": 1}""",
        ).replace(
            FIRST_ENTRY,
            """{"delay": 1}""",
        )
        val http = RecordingHttpClient.completed { jsonResponse(onlyDelayed) }
        val service = AnimeContentService(
            dataDirectory = temporaryDirectory,
            zoneId = TEST_ZONE,
            clock = TEST_CLOCK,
            httpClient = http,
            fontResource = bundledTestFont(),
            endpoint = endpoint,
        )

        val failure = assertFailsWith<ExecutionException> { service.todayImage().toCompletableFuture().get() }
        assertIs<NoAnimeContentException>(failure.deepestCause())
        assertEquals(1, http.requests().size)
        assertFalse(Files.exists(temporaryDirectory.resolve("anime-20260714.png")))
    }

    @Test
    fun normalizesProtocolRelativeCoverUrls() {
        val service = AnimeContentService(
            dataDirectory = temporaryDirectory,
            zoneId = TEST_ZONE,
            clock = TEST_CLOCK,
            httpClient = RecordingHttpClient.completed { jsonResponse(ANIME_JSON) },
            fontResource = bundledTestFont(),
            endpoint = URI.create("https://anime.example.test/timeline"),
        )
        val response = jsonResponse(
            ANIME_JSON
                .replace("\"date\": \"7-14\"", "\"date\": \"07-14\"")
                .replace("\"cover\": \"$FIRST_COVER\"", "\"square_cover\": \"//cover.example.test/one.png\""),
        )

        val items = service.parseTimeline(response, "20260714")

        assertEquals(FIRST_COVER, items.first().coverUri)
        assertEquals(2, items.size)
    }

    private companion object {
        val FIRST_COVER: URI = URI.create("https://cover.example.test/one.png")
        val SECOND_COVER: URI = URI.create("https://cover.example.test/two.png")

        const val FIRST_ENTRY =
            """{"title": "第一部动画", "pub_time": "09:00", "cover": "https://cover.example.test/one.png", "delay": 0}"""
        const val SECOND_ENTRY =
            """{"title": "第二部动画", "pub_time": "10:30", "cover": "https://cover.example.test/two.png", "delay": 0}"""

        val ANIME_JSON =
            """
            {
              "code": 0,
              "message": "success",
              "result": [
                {
                  "date": "7-14",
                  "seasons": [
                    $FIRST_ENTRY,
                    {"delay": 1},
                    $SECOND_ENTRY
                  ]
                }
              ]
            }
            """.trimIndent()
    }
}
