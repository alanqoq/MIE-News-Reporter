package com.mieai.qqbot.plugin.mienr

import com.mieai.qqbot.plugin.testkit.FakePluginLogger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticDispatchLedgerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `successful date survives restart and an older completion cannot replace it`() {
        val stateFile = temporaryDirectory.resolve("automatic-dispatch.properties")
        val logger = FakePluginLogger()
        val first = AutomaticDispatchLedger(stateFile, logger)
        val today = LocalDate.parse("2026-08-08")

        first.markSent(ReportKind.NEWS, "group-1", today)
        assertTrue(Files.isRegularFile(stateFile))
        assertTrue(AutomaticDispatchLedger(stateFile, logger).wasSent(ReportKind.NEWS, "group-1", today))

        first.markSent(ReportKind.NEWS, "group-1", today.plusDays(1))
        first.markSent(ReportKind.NEWS, "group-1", today)
        val reloaded = AutomaticDispatchLedger(stateFile, logger)
        assertTrue(reloaded.wasSent(ReportKind.NEWS, "group-1", today.plusDays(1)))
        assertFalse(reloaded.wasSent(ReportKind.NEWS, "group-1", today))
        assertTrue(logger.entries().isEmpty())
    }
}
