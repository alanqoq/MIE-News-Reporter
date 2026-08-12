package com.mieai.qqbot.plugin.mienr.content

import com.mieai.qqbot.plugin.mienr.writeFileAtomically
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.imageio.ImageIO

internal class DailyPngCache(
    private val directory: Path,
    private val prefix: String,
) {
    init {
        require(prefix.isNotBlank()) { "prefix must not be blank" }
        require(prefix.none { it == '/' || it == '\\' }) { "prefix must be a file-name prefix" }
    }

    fun read(dateKey: String): ByteArray? {
        val target = target(dateKey)
        deleteOldFiles(target)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null

        val bytes = Files.readAllBytes(target)
        if (!bytes.isReadablePng()) return null

        return bytes
    }

    fun deleteOld(dateKey: String) {
        deleteOldFiles(target(dateKey))
    }

    fun write(dateKey: String, bytes: ByteArray) {
        require(bytes.hasPngSignature()) { "cache content must be a PNG image" }
        val target = target(dateKey)
        writeFileAtomically(target, bytes)

        deleteOldFiles(target)
    }

    private fun target(dateKey: String): Path {
        require(DATE_KEY_REGEX.matches(dateKey)) { "dateKey must use yyyyMMdd" }
        return directory.resolve("$prefix-$dateKey.png")
    }

    private fun deleteOldFiles(current: Path) {
        if (!Files.isDirectory(directory)) return

        Files.newDirectoryStream(directory, "$prefix-*.png").use { entries ->
            entries.forEach { entry ->
                if (entry != current && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(entry)
                }
            }
        }
    }

    private companion object {
        val DATE_KEY_REGEX = Regex("\\d{8}")
    }
}

internal fun ByteArray.hasPngSignature(): Boolean =
    size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { this[it] == PNG_SIGNATURE[it] }

private fun ByteArray.isReadablePng(): Boolean {
    if (!hasPngSignature()) return false
    return try {
        val imageInput = ImageIO.createImageInputStream(ByteArrayInputStream(this)) ?: return false
        imageInput.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return false
            val reader = readers.next()
            try {
                reader.input = input
                reader.getWidth(0) > 0 && reader.getHeight(0) > 0
            } finally {
                reader.dispose()
            }
        }
    } catch (_: Exception) {
        false
    }
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
)
