package com.mieai.qqbot.plugin.mienr

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class ReportKind {
    NEWS,
    ANIME,
}

data class ReportConfiguration(
    val enabledGroups: Set<String>,
    val defaultHour: Int,
    val groupHours: Map<String, Int>,
    val disabledMessage: String,
    val failureMessage: String,
) {
    init {
        validateGroupIds(enabledGroups, "enabledGroups")
        validateHour(defaultHour, "defaultTime")
        groupHours.forEach { (groupId, hour) ->
            validateGroupId("groupTimes key", groupId)
            validateHour(hour, "groupTimes[$groupId]")
        }
        validateMessage("disabledMessage", disabledMessage)
        validateMessage("failureMessage", failureMessage)
    }

    fun immutableCopy(): ReportConfiguration = copy(
        enabledGroups = immutableSortedSet(enabledGroups),
        groupHours = immutableSortedMap(groupHours),
    )
}

data class CommandAliases(
    val mienr: Set<String> = emptySet(),
    val news: Set<String> = emptySet(),
    val timenews: Set<String> = emptySet(),
    val getnews: Set<String> = emptySet(),
    val anime: Set<String> = emptySet(),
    val timeanime: Set<String> = emptySet(),
    val getanime: Set<String> = emptySet(),
    val help: Set<String> = emptySet(),
) {
    init {
        val owners = mutableMapOf<String, String>()
        entries().forEach { (command, aliases) ->
            requireConfiguration(
                aliases.size <= 100,
                "commands.aliases.$command",
                "must not contain more than 100 aliases",
            )
            aliases.forEachIndexed { index, alias ->
                validateCommandAlias("commands.aliases.$command[$index]", alias)
                val normalized = alias.lowercase(Locale.ROOT)
                requireConfiguration(
                    normalized !in CANONICAL_COMMAND_NAMES,
                    "commands.aliases.$command[$index]",
                    "must not duplicate the canonical command $alias",
                )
                val previous = owners.putIfAbsent(normalized, command)
                requireConfiguration(
                    previous == null,
                    "commands.aliases.$command[$index]",
                    "duplicates an alias configured for $previous",
                )
            }
        }
    }

    fun immutableCopy(): CommandAliases = copy(
        mienr = immutableSortedSet(mienr),
        news = immutableSortedSet(news),
        timenews = immutableSortedSet(timenews),
        getnews = immutableSortedSet(getnews),
        anime = immutableSortedSet(anime),
        timeanime = immutableSortedSet(timeanime),
        getanime = immutableSortedSet(getanime),
        help = immutableSortedSet(help),
    )

    internal fun entries(): List<Pair<String, Set<String>>> = listOf(
        "mienr" to mienr,
        "news" to news,
        "timenews" to timenews,
        "getnews" to getnews,
        "anime" to anime,
        "timeanime" to timeanime,
        "getanime" to getanime,
        "help" to help,
    )

    private companion object {
        val CANONICAL_COMMAND_NAMES: Set<String> = setOf(
            "mienr",
            "news",
            "timenews",
            "getnews",
            "anime",
            "timeanime",
            "getanime",
            "help",
        )
    }
}

data class CommandConfiguration(
    val aliases: CommandAliases = CommandAliases(),
) {
    fun immutableCopy(): CommandConfiguration = copy(aliases = aliases.immutableCopy())
}

data class MienrConfiguration(
    val timeZone: String,
    val news: ReportConfiguration,
    val anime: ReportConfiguration,
    val commands: CommandConfiguration = CommandConfiguration(),
) {
    init {
        requireConfiguration(timeZone.isNotBlank(), "timeZone", "must not be blank")
        requireConfiguration(timeZone == timeZone.trim(), "timeZone", "must not have surrounding whitespace")
        try {
            ZoneId.of(timeZone)
        } catch (error: DateTimeException) {
            throw MienrConfigurationException("timeZone: unknown zone ID $timeZone")
        }
    }

    fun immutableCopy(): MienrConfiguration = copy(
        news = news.immutableCopy(),
        anime = anime.immutableCopy(),
        commands = commands.immutableCopy(),
    )

    fun report(kind: ReportKind): ReportConfiguration = when (kind) {
        ReportKind.NEWS -> news
        ReportKind.ANIME -> anime
    }

    companion object {
        const val DEFAULT_TIME_ZONE: String = "Asia/Shanghai"
        const val DEFAULT_HOUR: Int = 10
        const val MAX_MESSAGE_CODE_POINTS: Int = 4_000

        @JvmStatic
        fun defaults(): MienrConfiguration = MienrConfiguration(
            timeZone = DEFAULT_TIME_ZONE,
            news = ReportConfiguration(
                enabledGroups = emptySet(),
                defaultHour = DEFAULT_HOUR,
                groupHours = emptyMap(),
                disabledMessage = "本群尚未启用今日新闻推送，请联系群管理员使用 /mienr news 开启。",
                failureMessage = "今日新闻获取失败，请稍后重试。",
            ),
            anime = ReportConfiguration(
                enabledGroups = emptySet(),
                defaultHour = DEFAULT_HOUR,
                groupHours = emptyMap(),
                disabledMessage = "本群尚未启用今日番剧推送，请联系群管理员使用 /mienr anime 开启。",
                failureMessage = "今日番剧获取失败，请稍后重试。",
            ),
        ).immutableCopy()

        @JvmStatic
        fun parse(content: String): MienrConfiguration = MienrConfigurationCodec.parse(content)

        @JvmStatic
        fun render(configuration: MienrConfiguration): String = MienrConfigurationCodec.render(configuration)
    }
}

object MienrConfigurationCodec {
    private val rootKeys = setOf("timeZone", "commands", "news", "anime")
    private val requiredRootKeys = setOf("timeZone", "news", "anime")
    private val commandKeys = setOf("aliases")
    private val aliasKeys = setOf("mienr", "news", "timenews", "getnews", "anime", "timeanime", "getanime", "help")
    private val reportKeys = setOf("enabledGroups", "defaultTime", "groupTimes", "disabledMessage", "failureMessage")
    private val hourPattern = Regex("(?:[01][0-9]|2[0-3])")

    private fun newYaml(): Yaml = Yaml(
        SafeConstructor(
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 50
                codePointLimit = 1_000_000
            },
        ),
    )

    @JvmStatic
    fun parse(content: String): MienrConfiguration {
        if (content.isBlank()) throw MienrConfigurationException("configuration must not be blank")
        val root = parseSingleDocument(content)
        validateKeys(root, rootKeys, "configuration", requiredRootKeys)

        val timeZone = root.string("timeZone")
        val commands = root["commands"]?.let {
            parseCommands(root.mapping("commands"))
        } ?: CommandConfiguration()
        val news = parseReport(root.mapping("news"), "news")
        val anime = parseReport(root.mapping("anime"), "anime")
        return MienrConfiguration(timeZone, news, anime, commands).immutableCopy()
    }

    @JvmStatic
    fun render(configuration: MienrConfiguration): String {
        val value = configuration.immutableCopy()
        return buildString {
            appendLine("# 调度使用的 IANA 时区。默认按中国标准时间执行每日推送。")
            appendLine("timeZone: ${yamlString(value.timeZone)}")
            appendLine()
            renderCommands(value.commands)
            appendLine()
            renderReport("news", "今日新闻", "/mienr news", value.news)
            appendLine()
            renderReport("anime", "今日番剧", "/mienr anime", value.anime)
        }
    }

    private fun StringBuilder.renderCommands(commands: CommandConfiguration) {
        appendLine("commands:")
        appendLine("  # 别名不包含开头的 /；每条指令可配置多个别名，[] 表示没有别名。")
        appendLine("  aliases:")
        commands.aliases.entries().forEach { (command, aliases) ->
            if (aliases.isEmpty()) {
                appendLine("    $command: []")
            } else {
                appendLine("    $command:")
                aliases.forEach { appendLine("      - ${yamlString(it)}") }
            }
        }
    }

    private fun StringBuilder.renderReport(
        key: String,
        label: String,
        toggleCommand: String,
        report: ReportConfiguration,
    ) {
        appendLine("$key:")
        appendLine("  # 启用${label}的群 ID。默认不启用，由 $toggleCommand 维护。")
        if (report.enabledGroups.isEmpty()) {
            appendLine("  enabledGroups: []")
        } else {
            appendLine("  enabledGroups:")
            report.enabledGroups.forEach { appendLine("    - ${yamlString(it)}") }
        }
        appendLine("  # 未设置群独立时间时使用的每日推送小时，格式 HH（00-23）。")
        appendLine("  defaultTime: ${yamlString(formatHour(report.defaultHour))}")
        appendLine("  # 群独立推送小时；关闭该群推送时会一并删除对应记录。")
        if (report.groupHours.isEmpty()) {
            appendLine("  groupTimes: {}")
        } else {
            appendLine("  groupTimes:")
            report.groupHours.forEach { (groupId, hour) ->
                appendLine("    ${yamlString(groupId)}: ${yamlString(formatHour(hour))}")
            }
        }
        appendLine("  # 未启用群请求${label}时的引用回复。")
        appendLine("  disabledMessage: ${yamlString(report.disabledMessage)}")
        appendLine("  # 抓取、渲染或发送准备失败时的引用回复。")
        appendLine("  failureMessage: ${yamlString(report.failureMessage)}")
    }

    private fun parseSingleDocument(content: String): Map<*, *> {
        val root = try {
            val documents = newYaml().loadAll(content).toList()
            requireConfiguration(documents.size == 1, "document", "must contain exactly one YAML document")
            documents.single()
        } catch (error: MienrConfigurationException) {
            throw error
        } catch (error: YAMLException) {
            throw MienrConfigurationException("invalid YAML configuration: ${error.message ?: "syntax error"}")
        } catch (error: RuntimeException) {
            throw MienrConfigurationException("invalid YAML configuration: ${error.message ?: "syntax error"}")
        }
        return root as? Map<*, *>
            ?: throw MienrConfigurationException("configuration root must be a YAML mapping")
    }

    private fun parseReport(values: Map<*, *>, field: String): ReportConfiguration {
        validateKeys(values, reportKeys, field)
        val enabledGroups = parseGroups(values["enabledGroups"], "$field.enabledGroups")
        val defaultHour = parseHour(values["defaultTime"], "$field.defaultTime")
        val groupHours = parseGroupHours(values["groupTimes"], "$field.groupTimes")
        return ReportConfiguration(
            enabledGroups = enabledGroups,
            defaultHour = defaultHour,
            groupHours = groupHours,
            disabledMessage = values.string("disabledMessage", field),
            failureMessage = values.string("failureMessage", field),
        ).immutableCopy()
    }

    private fun parseCommands(values: Map<*, *>): CommandConfiguration {
        validateKeys(values, commandKeys, "commands")
        val aliases = values.mapping("aliases", "commands")
        validateKeys(aliases, aliasKeys, "commands.aliases")
        return CommandConfiguration(
            aliases = CommandAliases(
                mienr = parseAliases(aliases["mienr"], "commands.aliases.mienr"),
                news = parseAliases(aliases["news"], "commands.aliases.news"),
                timenews = parseAliases(aliases["timenews"], "commands.aliases.timenews"),
                getnews = parseAliases(aliases["getnews"], "commands.aliases.getnews"),
                anime = parseAliases(aliases["anime"], "commands.aliases.anime"),
                timeanime = parseAliases(aliases["timeanime"], "commands.aliases.timeanime"),
                getanime = parseAliases(aliases["getanime"], "commands.aliases.getanime"),
                help = parseAliases(aliases["help"], "commands.aliases.help"),
            ).immutableCopy(),
        )
    }

    private fun parseAliases(value: Any?, field: String): Set<String> {
        val sequence = value as? List<*>
            ?: throw MienrConfigurationException("$field must be a YAML sequence")
        val aliases = LinkedHashSet<String>(sequence.size)
        sequence.forEachIndexed { index, item ->
            val alias = item as? String
                ?: throw MienrConfigurationException("$field[$index] must be a string")
            if (!aliases.add(alias)) {
                throw MienrConfigurationException("$field[$index] duplicates alias $alias")
            }
        }
        return immutableSortedSet(aliases)
    }

    private fun parseGroups(value: Any?, field: String): Set<String> {
        val sequence = value as? List<*>
            ?: throw MienrConfigurationException("$field must be a YAML sequence")
        val groups = LinkedHashSet<String>(sequence.size)
        sequence.forEachIndexed { index, item ->
            val groupId = item as? String
                ?: throw MienrConfigurationException("$field[$index] must be a string")
            validateGroupId("$field[$index]", groupId)
            if (!groups.add(groupId)) {
                throw MienrConfigurationException("$field[$index] duplicates group ID $groupId")
            }
        }
        return immutableSortedSet(groups)
    }

    private fun parseGroupHours(value: Any?, field: String): Map<String, Int> {
        val mapping = value as? Map<*, *>
            ?: throw MienrConfigurationException("$field must be a YAML mapping")
        val parsed = TreeMap<String, Int>()
        mapping.forEach { (rawGroupId, rawHour) ->
            val groupId = rawGroupId as? String
                ?: throw MienrConfigurationException("$field keys must be strings")
            validateGroupId("$field key", groupId)
            parsed[groupId] = parseHour(rawHour, "$field[$groupId]")
        }
        return Collections.unmodifiableSortedMap(parsed)
    }

    private fun parseHour(value: Any?, field: String): Int {
        val text = value as? String
            ?: throw MienrConfigurationException("$field must be an HH string")
        if (!hourPattern.matches(text)) {
            throw MienrConfigurationException("$field must match HH in the range 00-23")
        }
        return text.toInt()
    }

    private fun validateKeys(
        values: Map<*, *>,
        expected: Set<String>,
        field: String,
        required: Set<String> = expected,
    ) {
        val keys = values.keys.map { key ->
            key as? String ?: throw MienrConfigurationException("$field keys must be strings")
        }.toSet()
        val unknown = keys - expected
        if (unknown.isNotEmpty()) {
            throw MienrConfigurationException("unknown $field field(s): ${unknown.sorted().joinToString(", ")}")
        }
        val missing = required - keys
        if (missing.isNotEmpty()) {
            throw MienrConfigurationException("missing $field field(s): ${missing.sorted().joinToString(", ")}")
        }
    }

    private fun Map<*, *>.mapping(key: String): Map<*, *> = this[key] as? Map<*, *>
        ?: throw MienrConfigurationException("$key must be a YAML mapping")

    private fun Map<*, *>.mapping(key: String, prefix: String): Map<*, *> = this[key] as? Map<*, *>
        ?: throw MienrConfigurationException("$prefix.$key must be a YAML mapping")

    private fun Map<*, *>.string(key: String, prefix: String? = null): String = this[key] as? String
        ?: throw MienrConfigurationException("${prefix?.let { "$it." }.orEmpty()}$key must be a string")
}

class MienrConfigurationStore private constructor(
    configurationFile: Path,
    initialConfiguration: MienrConfiguration,
) {
    val configurationFile: Path = configurationFile.toAbsolutePath().normalize()

    private val writeLock = ReentrantLock()
    private val current = AtomicReference(initialConfiguration.immutableCopy())

    fun snapshot(): MienrConfiguration = current.get()

    fun isEnabled(kind: ReportKind, groupId: String): Boolean {
        validateGroupId("groupId", groupId)
        return groupId in snapshot().report(kind).enabledGroups
    }

    fun scheduledHour(kind: ReportKind, groupId: String): Int {
        validateGroupId("groupId", groupId)
        val report = snapshot().report(kind)
        return report.groupHours[groupId] ?: report.defaultHour
    }

    /** Toggles a report and returns true when it is enabled after the update. */
    fun toggle(kind: ReportKind, groupId: String): Boolean {
        validateGroupId("groupId", groupId)
        var enabled = false
        update { configuration ->
            val report = configuration.report(kind)
            val groups = report.enabledGroups.toMutableSet()
            val groupHours = report.groupHours.toMutableMap()
            enabled = if (groups.add(groupId)) {
                true
            } else {
                groups.remove(groupId)
                groupHours.remove(groupId)
                false
            }
            configuration.withReport(
                kind,
                report.copy(enabledGroups = groups, groupHours = groupHours),
            )
        }
        return enabled
    }

    fun setGroupHour(kind: ReportKind, groupId: String, hour: Int): MienrConfiguration {
        validateGroupId("groupId", groupId)
        validateHour(hour, "hour")
        return update { configuration ->
            val report = configuration.report(kind)
            configuration.withReport(
                kind,
                report.copy(groupHours = report.groupHours + (groupId to hour)),
            )
        }
    }

    fun update(transform: (MienrConfiguration) -> MienrConfiguration): MienrConfiguration = writeLock.withLock {
        val next = transform(current.get()).immutableCopy()
        writeAtomically(configurationFile, MienrConfigurationCodec.render(next))
        current.set(next)
        next
    }

    companion object {
        @JvmStatic
        fun open(configurationFile: Path, configurationContent: String): MienrConfigurationStore =
            MienrConfigurationStore(configurationFile, MienrConfigurationCodec.parse(configurationContent))

        @JvmStatic
        fun load(configurationFile: Path): MienrConfigurationStore {
            val normalized = configurationFile.toAbsolutePath().normalize()
            return open(normalized, Files.readString(normalized, StandardCharsets.UTF_8))
        }

        private fun writeAtomically(target: Path, content: String) {
            val parent = target.parent ?: throw IOException("configuration file must have a parent directory")
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
            try {
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}

class MienrConfigurationException(message: String) : IllegalArgumentException(message)

private fun MienrConfiguration.withReport(kind: ReportKind, report: ReportConfiguration): MienrConfiguration = when (kind) {
    ReportKind.NEWS -> copy(news = report)
    ReportKind.ANIME -> copy(anime = report)
}

private fun validateGroupIds(groupIds: Set<String>, field: String) {
    groupIds.forEachIndexed { index, groupId -> validateGroupId("$field[$index]", groupId) }
}

private fun validateGroupId(field: String, value: String) {
    requireConfiguration(value.isNotBlank(), field, "must not be blank")
    requireConfiguration(value == value.trim(), field, "must not have leading or trailing whitespace")
    requireConfiguration(value.codePointCount(0, value.length) <= 255, field, "must not exceed 255 Unicode code points")
    requireConfiguration(value.codePoints().noneMatch(Character::isWhitespace), field, "must not contain whitespace")
    requireConfiguration(value.codePoints().noneMatch(Character::isISOControl), field, "must not contain control characters")
}

private fun validateHour(value: Int, field: String) {
    requireConfiguration(value in 0..23, field, "must be in the range 00-23")
}

private fun validateMessage(field: String, value: String) {
    requireConfiguration(value.isNotBlank(), field, "must not be blank")
    requireConfiguration(
        value.codePointCount(0, value.length) <= MienrConfiguration.MAX_MESSAGE_CODE_POINTS,
        field,
        "must not exceed ${MienrConfiguration.MAX_MESSAGE_CODE_POINTS} Unicode code points",
    )
    requireConfiguration(value.codePoints().noneMatch(::unsupportedControl), field, "contains an unsupported control character")
}

private fun validateCommandAlias(field: String, value: String) {
    requireConfiguration(value.isNotBlank(), field, "must not be blank")
    requireConfiguration(value == value.trim(), field, "must not have leading or trailing whitespace")
    requireConfiguration(value.codePointCount(0, value.length) <= 100, field, "must not exceed 100 Unicode code points")
    requireConfiguration('/' !in value, field, "must not contain /")
    requireConfiguration(value.codePoints().noneMatch(Character::isWhitespace), field, "must not contain whitespace")
    requireConfiguration(value.codePoints().noneMatch(Character::isISOControl), field, "must not contain control characters")
}

private fun requireConfiguration(condition: Boolean, field: String, detail: String) {
    if (!condition) throw MienrConfigurationException("$field: $detail")
}

private fun unsupportedControl(codePoint: Int): Boolean =
    Character.isISOControl(codePoint) && codePoint != '\n'.code && codePoint != '\r'.code && codePoint != '\t'.code

private fun immutableSortedSet(source: Collection<String>): Set<String> =
    Collections.unmodifiableSortedSet(TreeSet(source))

private fun immutableSortedMap(source: Map<String, Int>): Map<String, Int> =
    Collections.unmodifiableSortedMap(TreeMap(source))

private fun formatHour(hour: Int): String = String.format(Locale.ROOT, "%02d", hour)

private fun yamlString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (Character.isISOControl(character)) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
