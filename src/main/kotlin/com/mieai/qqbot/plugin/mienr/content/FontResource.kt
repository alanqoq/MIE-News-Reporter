package com.mieai.qqbot.plugin.mienr.content

import java.io.ByteArrayInputStream
import java.io.InputStream

/** Opens a fresh stream for a font bundled in the plugin JAR. */
fun interface FontResource {
    fun open(): InputStream

    companion object {
        /** Resolves [resourcePath] relative to [anchor] each time the font is requested. */
        @JvmStatic
        fun classpath(anchor: Class<*>, resourcePath: String): FontResource {
            require(resourcePath.isNotBlank()) { "resourcePath must not be blank" }
            return FontResource {
                anchor.getResourceAsStream(resourcePath)
                    ?: throw ContentRenderException("Missing bundled font resource: $resourcePath")
            }
        }

        /** Adapts one stream (read eagerly) to the repeatable resource contract. */
        @JvmStatic
        fun fromInputStream(stream: InputStream): FontResource {
            val bytes = stream.use { it.readBytes() }
            require(bytes.isNotEmpty()) { "font resource must not be empty" }
            return FontResource { ByteArrayInputStream(bytes) }
        }

        @JvmStatic
        fun fromBytes(bytes: ByteArray): FontResource {
            require(bytes.isNotEmpty()) { "font resource must not be empty" }
            val copy = bytes.clone()
            return FontResource { ByteArrayInputStream(copy) }
        }
    }
}

/** Base failure for content parsing, fetching, caching, or rendering. */
open class ContentServiceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ContentRenderException(message: String, cause: Throwable? = null) : ContentServiceException(message, cause)
