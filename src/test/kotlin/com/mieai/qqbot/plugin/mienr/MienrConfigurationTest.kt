package com.mieai.qqbot.plugin.mienr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MienrConfigurationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `default resource is valid and disables every group`() {
        val configuration = MienrConfiguration.parse(defaultConfiguration())

        assertEquals("Asia/Shanghai", configuration.timeZone)
        assertEquals(10, configuration.news.defaultHour)
        assertEquals(10, configuration.anime.defaultHour)
        assertTrue(configuration.news.enabledGroups.isEmpty())
        assertTrue(configuration.anime.enabledGroups.isEmpty())
        assertTrue(configuration.commands.aliases.entries().all { (_, aliases) -> aliases.isEmpty() })
    }

    @Test
    fun `rendered configuration round trips and uses strict HH strings`() {
        val configuration = MienrConfiguration.defaults().copy(
            commands = CommandConfiguration(
                aliases = CommandAliases(
                    mienr = setOf("日报"),
                    anime = setOf("开启今日番剧"),
                ),
            ),
            news = MienrConfiguration.defaults().news.copy(
                enabledGroups = setOf("group-b", "group-a"),
                defaultHour = 0,
                groupHours = mapOf("group-b" to 23, "group-a" to 7),
            ),
        )

        val rendered = MienrConfiguration.render(configuration)

        assertEquals(configuration.immutableCopy(), MienrConfiguration.parse(rendered))
        assertTrue(rendered.contains("defaultTime: \"00\""))
        assertTrue(rendered.contains("\"group-a\": \"07\""))
        assertTrue(rendered.indexOf("group-a") < rendered.indexOf("group-b"))
        assertTrue(rendered.contains("    mienr:\n      - \"日报\""))
        assertTrue(rendered.contains("    anime:\n      - \"开启今日番剧\""))
    }

    @Test
    fun `legacy configuration without commands uses empty aliases`() {
        val legacy = defaultConfiguration().replace(
            Regex("(?ms)^commands:\\n.*?(?=^news:)"),
            "",
        )

        val configuration = MienrConfiguration.parse(legacy)

        assertTrue(configuration.commands.aliases.entries().all { (_, aliases) -> aliases.isEmpty() })
    }

    @Test
    fun `rejects unsafe conflicting and incomplete aliases`() {
        assertFailsWith<MienrConfigurationException> {
            CommandAliases(anime = setOf("/开启今日番剧"))
        }
        assertFailsWith<MienrConfigurationException> {
            CommandAliases(anime = setOf("开启 今日番剧"))
        }
        assertFailsWith<MienrConfigurationException> {
            CommandAliases(news = setOf("开关"), anime = setOf("开关"))
        }
        assertFailsWith<MienrConfigurationException> {
            CommandAliases(news = setOf("Daily"), anime = setOf("daily"))
        }
        assertFailsWith<MienrConfigurationException> {
            CommandAliases(anime = setOf("news"))
        }

        val incomplete = defaultConfiguration().replaceFirst("    help: []\n", "")
        assertFailsWith<MienrConfigurationException> { MienrConfiguration.parse(incomplete) }
    }

    @Test
    fun `toggle and group hour changes are persisted atomically`() {
        val configurationFile = temporaryDirectory.resolve("config.yml")
        val aliases = CommandAliases(news = setOf("新闻开关"))
        val initial = MienrConfiguration.defaults().copy(
            commands = CommandConfiguration(aliases),
        )
        val store = MienrConfigurationStore.open(configurationFile, MienrConfiguration.render(initial))

        assertTrue(store.toggle(ReportKind.NEWS, "group-1"))
        store.setGroupHour(ReportKind.NEWS, "group-1", 23)
        assertTrue(store.isEnabled(ReportKind.NEWS, "group-1"))
        assertEquals(23, store.scheduledHour(ReportKind.NEWS, "group-1"))

        val enabledOnDisk = MienrConfiguration.parse(Files.readString(configurationFile))
        assertEquals(setOf("group-1"), enabledOnDisk.news.enabledGroups)
        assertEquals(mapOf("group-1" to 23), enabledOnDisk.news.groupHours)
        assertEquals(aliases, enabledOnDisk.commands.aliases)

        assertFalse(store.toggle(ReportKind.NEWS, "group-1"))
        val disabledOnDisk = MienrConfiguration.parse(Files.readString(configurationFile))
        assertTrue(disabledOnDisk.news.enabledGroups.isEmpty())
        assertTrue(disabledOnDisk.news.groupHours.isEmpty())
        assertTrue(disabledOnDisk.anime.enabledGroups.isEmpty())
        assertEquals(aliases, disabledOnDisk.commands.aliases)
    }

    @Test
    fun `rejects malformed hours duplicate keys and unknown time zones`() {
        val source = defaultConfiguration()

        listOf("0", "9", "24", "-1").forEach { invalidHour ->
            val invalid = source.replaceFirst("defaultTime: \"10\"", "defaultTime: \"$invalidHour\"")
            assertFailsWith<MienrConfigurationException> { MienrConfiguration.parse(invalid) }
        }
        assertFailsWith<MienrConfigurationException> {
            MienrConfiguration.parse(source.replaceFirst("defaultTime: \"10\"", "defaultTime: 10"))
        }
        assertFailsWith<MienrConfigurationException> {
            MienrConfiguration.parse(source.replaceFirst("timeZone: \"Asia/Shanghai\"", "timeZone: \"bad zone\""))
        }
        assertFailsWith<MienrConfigurationException> {
            MienrConfiguration.parse("$source\ntimeZone: \"UTC\"\n")
        }
    }

    private fun defaultConfiguration(): String = requireNotNull(
        javaClass.getResourceAsStream("/config.yml"),
    ) { "missing config.yml test resource" }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
