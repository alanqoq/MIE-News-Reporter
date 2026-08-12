package com.mieai.qqbot.plugin.mienr

import com.mieai.qqbot.plugin.api.PluginLogger
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.LocalDate
import java.util.Base64
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Persists successful automatic sends so a restart within the same hour cannot duplicate them. */
internal class AutomaticDispatchLedger(
    stateFile: Path,
    private val logger: PluginLogger,
) {
    private val stateFile = stateFile.toAbsolutePath().normalize()
    private val lock = ReentrantLock()
    private val sentDates = load()

    fun wasSent(kind: ReportKind, groupId: String, date: LocalDate): Boolean = lock.withLock {
        sentDates[key(kind, groupId)] == date
    }

    fun markSent(kind: ReportKind, groupId: String, date: LocalDate) = lock.withLock {
        sentDates.entries.removeIf { it.value.isBefore(date.minusDays(1)) }
        val key = key(kind, groupId)
        if (sentDates[key]?.isAfter(date) != true) sentDates[key] = date
        try {
            writeAtomically()
        } catch (failure: Exception) {
            logger.error("Could not persist automatic dispatch state", failure)
        }
    }

    private fun load(): MutableMap<String, LocalDate> {
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) return mutableMapOf()
        return try {
            val properties = Properties()
            Files.newBufferedReader(stateFile, StandardCharsets.UTF_8).use(properties::load)
            val loaded = mutableMapOf<String, LocalDate>()
            for (key in properties.stringPropertyNames()) {
                val date = runCatching { LocalDate.parse(properties.getProperty(key)) }.getOrNull()
                if (date == null) {
                    logger.warn("Ignoring invalid automatic dispatch state entry: $key")
                } else {
                    loaded[key] = date
                }
            }
            loaded
        } catch (failure: Exception) {
            logger.warn("Automatic dispatch state could not be read; starting with an empty state")
            mutableMapOf()
        }
    }

    private fun writeAtomically() {
        val properties = Properties()
        sentDates.toSortedMap().forEach { (key, date) -> properties.setProperty(key, date.toString()) }
        val bytes = ByteArrayOutputStream().use { output ->
            properties.store(output, "MIE News Reporter automatic dispatch state")
            output.toByteArray()
        }
        writeFileAtomically(stateFile, bytes)
    }

    private fun key(kind: ReportKind, groupId: String): String {
        val encodedGroup = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(groupId.toByteArray(StandardCharsets.UTF_8))
        return "${kind.name.lowercase()}.$encodedGroup"
    }
}
