package com.mieai.qqbot.plugin.mienr

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MienrCommandTest {
    @Test
    fun `parses every supported command`() {
        assertEquals(MienrCommand.Toggle(ReportKind.NEWS), MienrCommandParser.parse(" /mienr news "))
        assertEquals(MienrCommand.Toggle(ReportKind.ANIME), MienrCommandParser.parse("/MIENR ANIME"))
        assertEquals(MienrCommand.Get(ReportKind.NEWS), MienrCommandParser.parse("/mienr getnews"))
        assertEquals(MienrCommand.Get(ReportKind.ANIME), MienrCommandParser.parse("/mienr getanime"))
        assertEquals(MienrCommand.SetTime(ReportKind.NEWS, 0), MienrCommandParser.parse("/mienr timenews 00"))
        assertEquals(MienrCommand.SetTime(ReportKind.ANIME, 23), MienrCommandParser.parse("/mienr timeanime 23"))
        assertEquals(MienrCommand.Help, MienrCommandParser.parse("/mienr help"))
    }

    @Test
    fun `rejects invalid hours and extra arguments with focused usage`() {
        listOf("0", "9", "24", "-1", "ab").forEach { hour ->
            assertEquals(
                MienrCommand.Invalid("/mienr timenews HH"),
                MienrCommandParser.parse("/mienr timenews $hour"),
            )
        }
        assertEquals(
            MienrCommand.Invalid("/mienr timeanime HH"),
            MienrCommandParser.parse("/mienr timeanime 10 extra"),
        )
        assertEquals(MienrCommand.Invalid("/mienr news"), MienrCommandParser.parse("/mienr news extra"))
    }

    @Test
    fun `parses main and direct command aliases`() {
        val aliases = CommandAliases(
            mienr = setOf("日报"),
            news = setOf("新闻开关", "DailyNews"),
            timenews = setOf("设置新闻时间"),
            getnews = setOf("获取今日新闻"),
            anime = setOf("开启今日番剧"),
            timeanime = setOf("设置番剧时间"),
            getanime = setOf("获取今日番剧"),
            help = setOf("帮助"),
        )

        assertEquals(MienrCommand.Toggle(ReportKind.ANIME), MienrCommandParser.parse("/开启今日番剧", aliases))
        assertEquals(MienrCommand.Toggle(ReportKind.NEWS), MienrCommandParser.parse("/dailynews", aliases))
        assertEquals(
            MienrCommand.SetTime(ReportKind.NEWS, 8),
            MienrCommandParser.parse("/设置新闻时间 08", aliases),
        )
        assertEquals(MienrCommand.Get(ReportKind.NEWS), MienrCommandParser.parse("/日报 getnews", aliases))
        assertEquals(MienrCommand.Toggle(ReportKind.ANIME), MienrCommandParser.parse("/日报 开启今日番剧", aliases))
        assertEquals(MienrCommand.Help, MienrCommandParser.parse("/日报 帮助", aliases))
    }

    @Test
    fun `alias errors use canonical usage and unrelated slash commands stay ignored`() {
        val aliases = CommandAliases(
            mienr = setOf("日报"),
            news = setOf("新闻开关"),
            timenews = setOf("设置新闻时间"),
        )

        assertEquals(MienrCommand.Invalid("/mienr help"), MienrCommandParser.parse("/日报", aliases))
        assertEquals(MienrCommand.Invalid("/mienr news"), MienrCommandParser.parse("/新闻开关 extra", aliases))
        assertEquals(MienrCommand.Invalid("/mienr timenews HH"), MienrCommandParser.parse("/设置新闻时间 8", aliases))
        assertNull(MienrCommandParser.parse("/未配置指令", aliases))
    }

    @Test
    fun `ignores unrelated text and routes unknown subcommands to help`() {
        assertNull(MienrCommandParser.parse(null))
        assertNull(MienrCommandParser.parse(""))
        assertNull(MienrCommandParser.parse("/mienr-news"))
        assertNull(MienrCommandParser.parse("hello"))
        assertEquals(MienrCommand.Invalid("/mienr help"), MienrCommandParser.parse("/mienr"))
        assertEquals(MienrCommand.Invalid("/mienr help"), MienrCommandParser.parse("/mienr unknown"))
    }

    @Test
    fun `help names every command`() {
        listOf("news", "timenews", "getnews", "anime", "timeanime", "getanime", "help").forEach { command ->
            assertTrue(MIENR_HELP_TEXT.contains("/mienr $command"))
        }
        assertIs<MienrCommand.Help>(MienrCommandParser.parse("/mienr help"))
    }
}
