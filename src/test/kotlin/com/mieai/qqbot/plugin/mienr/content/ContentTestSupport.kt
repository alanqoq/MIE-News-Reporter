package com.mieai.qqbot.plugin.mienr.content

import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
internal val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-14T02:00:00Z"), TEST_ZONE)

internal fun bundledTestFont(): FontResource =
    FontResource.classpath(ContentTestAnchor::class.java, "/chinese_font.ttf")

internal fun jsonResponse(json: String, status: Int = 200): PluginHttpResponse =
    PluginHttpResponse(
        status,
        mapOf("content-type" to listOf("application/json; charset=utf-8")),
        json.toByteArray(StandardCharsets.UTF_8),
    )

internal fun imageResponse(bytes: ByteArray, status: Int = 200): PluginHttpResponse =
    PluginHttpResponse(status, mapOf("content-type" to listOf("image/png")), bytes)

internal fun fixturePng(width: Int = 320, height: Int = 480, color: Color = Color(0x6A, 0x91, 0xC8)): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().let { graphics ->
        try {
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.WHITE
            graphics.fillOval(width / 4, height / 4, width / 2, width / 2)
        } finally {
            graphics.dispose()
        }
    }
    return ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }
}

internal fun decodePng(bytes: ByteArray): BufferedImage =
    ByteArrayInputStream(bytes).use { input -> requireNotNull(ImageIO.read(input)) }

internal fun <T> CompletionStage<T>.await(): T =
    toCompletableFuture().get(20, TimeUnit.SECONDS)

internal fun Throwable.deepestCause(): Throwable {
    var deepest = this
    while (deepest.cause != null && deepest.cause !== deepest) {
        deepest = deepest.cause!!
    }
    return deepest
}

internal class RecordingHttpClient(
    private val responder: (PluginHttpRequest) -> CompletionStage<PluginHttpResponse>,
) : PluginHttpClient {
    private val recorded = mutableListOf<PluginHttpRequest>()

    override fun send(request: PluginHttpRequest): CompletionStage<PluginHttpResponse> {
        synchronized(recorded) { recorded += request }
        return responder(request)
    }

    fun requests(): List<PluginHttpRequest> = synchronized(recorded) { recorded.toList() }

    companion object {
        fun completed(responder: (PluginHttpRequest) -> PluginHttpResponse): RecordingHttpClient =
            RecordingHttpClient { request ->
                try {
                    CompletableFuture.completedFuture(responder(request))
                } catch (failure: RuntimeException) {
                    CompletableFuture.failedFuture(failure)
                }
            }
    }
}

private object ContentTestAnchor
