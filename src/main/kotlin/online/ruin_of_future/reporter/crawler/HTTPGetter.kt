package online.ruin_of_future.reporter.crawler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class HTTPGetter {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        execute(url) { body ->
            val bytes = readLimited(body, MAX_TEXT_RESPONSE_BYTES)
            val charset = body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
            bytes.toString(charset)
        }
    }

    suspend fun getRaw(url: String): ByteArray = withContext(Dispatchers.IO) {
        execute(url) { body -> readLimited(body, MAX_BINARY_RESPONSE_BYTES) }
    }

    private fun <T> execute(url: String, readBody: (ResponseBody) -> T): T {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Empty HTTP response for $url")
            readBody(body)
        }
    }

    private fun readLimited(body: ResponseBody, maxBytes: Int): ByteArray {
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            throw IOException("HTTP response exceeds $maxBytes bytes")
        }

        val bytes = body.byteStream().use { it.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) {
            throw IOException("HTTP response exceeds $maxBytes bytes")
        }
        return bytes
    }

    companion object {
        private const val MAX_TEXT_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_BINARY_RESPONSE_BYTES = 20 * 1024 * 1024
    }
}
