package com.mieai.qqbot.plugin.mienr

import java.util.Locale

internal sealed interface MienrCommand {
    data class Toggle(val kind: ReportKind) : MienrCommand

    data class SetTime(val kind: ReportKind, val hour: Int) : MienrCommand

    data class Get(val kind: ReportKind) : MienrCommand

    data object Help : MienrCommand

    data class Invalid(val usage: String) : MienrCommand
}

internal object MienrCommandParser {
    private val whitespace = Regex("\\s+")
    private val hourPattern = Regex("(?:[01][0-9]|2[0-3])")
    private val mentionPrefix = Regex("^<@!?[^>]+>\\s*")

    fun parse(
        content: String?,
        aliases: CommandAliases = CommandAliases(),
        mentioned: Boolean = false,
    ): MienrCommand? {
        val value = content?.trim().orEmpty().let { if (mentioned) mentionPrefix.replaceFirst(it, "") else it }
        if (value.isEmpty()) return null

        val arguments = value.split(whitespace)
        val invocation = arguments.first()
        if (!invocation.startsWith('/') || invocation.length == 1) return null

        val commandName = invocation.substring(1)
        if (commandName.equals("mienr", ignoreCase = true) || aliases.mienr.matches(commandName)) {
            return parsePrefixed(arguments, aliases)
        }

        val directCommand = resolveAlias(commandName, aliases) ?: return null
        return parseCommand(directCommand, arguments.drop(1))
    }

    private fun parsePrefixed(arguments: List<String>, aliases: CommandAliases): MienrCommand {
        if (arguments.size == 1) return MienrCommand.Invalid("/mienr help")
        val command = resolveCanonical(arguments[1]) ?: resolveAlias(arguments[1], aliases)
            ?: return MienrCommand.Invalid("/mienr help")
        return parseCommand(command, arguments.drop(2))
    }

    private fun parseCommand(command: CommandName, arguments: List<String>): MienrCommand = when (command) {
        CommandName.NEWS -> withoutArguments(arguments, MienrCommand.Toggle(ReportKind.NEWS), "/mienr news")
        CommandName.ANIME -> withoutArguments(arguments, MienrCommand.Toggle(ReportKind.ANIME), "/mienr anime")
        CommandName.GET_NEWS -> withoutArguments(arguments, MienrCommand.Get(ReportKind.NEWS), "/mienr getnews")
        CommandName.GET_ANIME -> withoutArguments(arguments, MienrCommand.Get(ReportKind.ANIME), "/mienr getanime")
        CommandName.HELP -> withoutArguments(arguments, MienrCommand.Help, "/mienr help")
        CommandName.TIME_NEWS -> parseTime(arguments, ReportKind.NEWS, "/mienr timenews HH")
        CommandName.TIME_ANIME -> parseTime(arguments, ReportKind.ANIME, "/mienr timeanime HH")
    }

    private fun resolveCanonical(value: String): CommandName? = when (value.lowercase(Locale.ROOT)) {
        "news" -> CommandName.NEWS
        "timenews" -> CommandName.TIME_NEWS
        "getnews" -> CommandName.GET_NEWS
        "anime" -> CommandName.ANIME
        "timeanime" -> CommandName.TIME_ANIME
        "getanime" -> CommandName.GET_ANIME
        "help" -> CommandName.HELP
        else -> null
    }

    private fun resolveAlias(value: String, aliases: CommandAliases): CommandName? = when {
        aliases.news.matches(value) -> CommandName.NEWS
        aliases.timenews.matches(value) -> CommandName.TIME_NEWS
        aliases.getnews.matches(value) -> CommandName.GET_NEWS
        aliases.anime.matches(value) -> CommandName.ANIME
        aliases.timeanime.matches(value) -> CommandName.TIME_ANIME
        aliases.getanime.matches(value) -> CommandName.GET_ANIME
        aliases.help.matches(value) -> CommandName.HELP
        else -> null
    }

    private fun Set<String>.matches(value: String): Boolean = any {
        it.equals(value, ignoreCase = true)
    }

    private enum class CommandName {
        NEWS,
        TIME_NEWS,
        GET_NEWS,
        ANIME,
        TIME_ANIME,
        GET_ANIME,
        HELP,
    }

    private fun withoutArguments(
        arguments: List<String>,
        command: MienrCommand,
        usage: String,
    ): MienrCommand = if (arguments.isEmpty()) command else MienrCommand.Invalid(usage)

    private fun parseTime(arguments: List<String>, kind: ReportKind, usage: String): MienrCommand {
        if (arguments.size != 1 || !hourPattern.matches(arguments[0])) {
            return MienrCommand.Invalid(usage)
        }
        return MienrCommand.SetTime(kind, arguments[0].toInt())
    }
}

internal const val MIENR_HELP_TEXT: String = """MIE News Reporter 指令
/mienr news - 开启或关闭本群今日新闻推送（管理员/群主）
/mienr timenews HH - 设置本群新闻推送小时，00-23（管理员/群主）
/mienr getnews - 获取本群今日新闻
/mienr anime - 开启或关闭本群今日番剧推送（管理员/群主）
/mienr timeanime HH - 设置本群番剧推送小时，00-23（管理员/群主）
/mienr getanime - 获取本群今日番剧
/mienr help - 查看本帮助"""
