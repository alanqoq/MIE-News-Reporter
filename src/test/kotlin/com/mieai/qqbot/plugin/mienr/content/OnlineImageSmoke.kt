package com.mieai.qqbot.plugin.mienr.content

import com.mieai.qqbot.plugin.api.PluginHttpClient
import com.mieai.qqbot.plugin.api.PluginHttpRequest
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Explicit live smoke runner; it is intentionally excluded from the default JUnit suite. */
object OnlineImageSmoke {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val outputDirectory = Path.of(arguments.singleOrNull() ?: "generated-examples")
            .toAbsolutePath()
            .normalize()
        Files.createDirectories(outputDirectory)

        val zoneId = ZoneId.of("Asia/Shanghai")
        val clock = Clock.system(zoneId)
        val dateKey = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
        val client = JdkPluginHttpClient()
        val font = FontResource.classpath(OnlineImageSmoke::class.java, "/chinese_font.ttf")

        val results = listOf(
            generate("news", outputDirectory.resolve("news-$dateKey.png")) {
                NewsContentService(outputDirectory, zoneId, clock, client, font).todayImage()
            },
            generate("anime", outputDirectory.resolve("anime-$dateKey.png")) {
                AnimeContentService(outputDirectory, zoneId, clock, client, font).todayImage()
            },
        )
        if (results.any { !it }) error("One or more online image generators failed")
    }

    private fun generate(
        name: String,
        target: Path,
        operation: () -> CompletionStage<ByteArray>,
    ): Boolean = try {
        val bytes = operation().toCompletableFuture().get(3, TimeUnit.MINUTES)
        val image = ByteArrayInputStream(bytes).use { input ->
            requireNotNull(ImageIO.read(input)) { "$name result is not a supported image" }
        }
        Files.write(target, bytes)
        println("$name: OK ${image.width}x${image.height}, ${bytes.size} bytes, $target")
        true
    } catch (failure: Throwable) {
        val cause = failure.deepestCause()
        System.err.println("$name: FAILED ${cause.javaClass.simpleName}: ${cause.message}")
        false
    }

    private fun Throwable.deepestCause(): Throwable {
        var current = this
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private class JdkPluginHttpClient : PluginHttpClient {
        private val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()

        override fun send(request: PluginHttpRequest): CompletionStage<PluginHttpResponse> {
            val builder = HttpRequest.newBuilder(request.uri).timeout(request.timeout)
            request.headers.forEach(builder::header)
            val publisher = request.body?.let(HttpRequest.BodyPublishers::ofByteArray)
                ?: HttpRequest.BodyPublishers.noBody()
            val outbound = builder.method(request.method, publisher).build()
            return client.sendAsync(outbound, HttpResponse.BodyHandlers.ofByteArray()).thenApply { response ->
                PluginHttpResponse(response.statusCode(), response.headers().map(), response.body())
            }
        }
    }
}
