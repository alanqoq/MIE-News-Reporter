package com.mieai.qqbot.plugin.mienr

import com.mieai.qqbot.plugin.api.EventSubscription
import com.mieai.qqbot.plugin.api.GroupMemberRole
import com.mieai.qqbot.plugin.api.InboundMessage
import com.mieai.qqbot.plugin.api.MediaKind
import com.mieai.qqbot.plugin.api.MediaUpload
import com.mieai.qqbot.plugin.api.MessageReference
import com.mieai.qqbot.plugin.api.MessageSendOptions
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import com.mieai.qqbot.plugin.api.PluginTask
import com.mieai.qqbot.plugin.api.StagedMediaMessage
import com.mieai.qqbot.plugin.api.TextMessage
import com.mieai.qqbot.plugin.mienr.content.AnimeContentService
import com.mieai.qqbot.plugin.mienr.content.FontResource
import com.mieai.qqbot.plugin.mienr.content.NewsContentService
import com.mieai.qqbot.plugin.spi.BotPlugin
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

class MienrPluginFactory : BotPluginFactory {
    override val pluginId: String = "mienr"

    override fun create(context: PluginRuntimeContext): BotPlugin {
        val store = MienrConfigurationStore.open(
            context.configurationFile,
            context.configuration.content,
        )
        val zoneId = ZoneId.of(store.snapshot().timeZone)
        val clock = Clock.system(zoneId)
        val font = FontResource.classpath(MienrPluginFactory::class.java, "/chinese_font.ttf")
        val news = NewsContentService(
            dataDirectory = context.base.dataDirectory,
            zoneId = zoneId,
            clock = clock,
            httpClient = context.httpClient,
            fontResource = font,
        )
        val anime = AnimeContentService(
            dataDirectory = context.base.dataDirectory,
            zoneId = zoneId,
            clock = clock,
            httpClient = context.httpClient,
            fontResource = font,
        )
        return MienrPlugin(
            context = context,
            configuration = store,
            clock = clock,
            newsImage = object : DailyImageProvider {
                override fun todayImage(): CompletionStage<ByteArray> = news.todayImage()

                override fun cleanupExpiredImages() = news.cleanupExpiredImages()
            },
            animeImage = object : DailyImageProvider {
                override fun todayImage(): CompletionStage<ByteArray> = anime.todayImage()

                override fun cleanupExpiredImages() = anime.cleanupExpiredImages()
            },
        )
    }
}

internal fun interface DailyImageProvider {
    fun todayImage(): CompletionStage<ByteArray>

    fun cleanupExpiredImages() = Unit
}

internal class MienrPlugin(
    private val context: PluginRuntimeContext,
    private val configuration: MienrConfigurationStore,
    private val clock: Clock,
    private val newsImage: DailyImageProvider,
    private val animeImage: DailyImageProvider,
    private val dispatchLedger: AutomaticDispatchLedger = AutomaticDispatchLedger(
        context.base.dataDirectory.resolve("automatic-dispatch.properties"),
        context.base.logger,
    ),
) : BotPlugin {
    private val subscriptions = mutableListOf<EventSubscription>()
    private val automaticInFlight = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var running = false
    private var lastCleanupDate: LocalDate? = null
    private var scheduleTask: PluginTask? = null

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        try {
            subscriptions += context.events.subscribe("mienr-commands", MESSAGE_EVENT_TYPES, ::handleEvent)
            scheduleTask = context.scheduler.scheduleWithFixedDelay(
                Duration.ZERO,
                Duration.ofMinutes(1),
                ::runScheduleTick,
            )
        } catch (failure: Throwable) {
            scheduleTask?.close()
            scheduleTask = null
            subscriptions.forEach(EventSubscription::close)
            subscriptions.clear()
            running = false
            throw failure
        }
    }

    private fun handleEvent(event: PluginEvent): CompletionStage<Void> {
        if (!running) return completedVoid()
        val inbound = event.message ?: return completedVoid()
        if (inbound.replyTarget.type != MessageTargetType.GROUP) return completedVoid()

        return when (val command = MienrCommandParser.parse(inbound.content, configuration.snapshot().commands.aliases)) {
            null -> completedVoid()
            is MienrCommand.Help -> sendQuotedText(event, MIENR_HELP_TEXT, "help")
            is MienrCommand.Invalid -> sendQuotedText(event, "用法：${command.usage}", "invalid")
            is MienrCommand.Toggle -> handleToggle(event, inbound, command.kind)
            is MienrCommand.SetTime -> handleSetTime(event, inbound, command.kind, command.hour)
            is MienrCommand.Get -> handleGet(event, command.kind)
        }
    }

    private fun handleToggle(event: PluginEvent, inbound: InboundMessage, kind: ReportKind): CompletionStage<Void> {
        if (!isAdministrator(inbound)) return sendQuotedText(event, PERMISSION_MESSAGE, "permission")
        return try {
            val enabled = configuration.toggle(kind, inbound.replyTarget.id)
            val label = kind.label()
            val status = if (enabled) "已开启" else "已关闭"
            sendQuotedText(event, "${status}${label}推送。", "toggle-${kind.name.lowercase()}")
        } catch (failure: Throwable) {
            logFailure("Could not update ${kind.name.lowercase()} toggle", failure)
            sendQuotedText(event, CONFIG_FAILURE_MESSAGE, "config-failure")
        }
    }

    private fun handleSetTime(
        event: PluginEvent,
        inbound: InboundMessage,
        kind: ReportKind,
        hour: Int,
    ): CompletionStage<Void> {
        if (!isAdministrator(inbound)) return sendQuotedText(event, PERMISSION_MESSAGE, "permission")
        return try {
            configuration.setGroupHour(kind, inbound.replyTarget.id, hour)
            sendQuotedText(
                event,
                "已将${kind.label()}每日推送时间设为 ${hour.toString().padStart(2, '0')}时。",
                "time-${kind.name.lowercase()}",
            )
        } catch (failure: Throwable) {
            logFailure("Could not update ${kind.name.lowercase()} schedule", failure)
            sendQuotedText(event, CONFIG_FAILURE_MESSAGE, "config-failure")
        }
    }

    private fun handleGet(event: PluginEvent, kind: ReportKind): CompletionStage<Void> {
        val inbound = requireNotNull(event.message)
        val groupId = inbound.replyTarget.id
        if (!configuration.isEnabled(kind, groupId)) {
            return sendQuotedText(event, configuration.snapshot().report(kind).disabledMessage, "disabled")
        }

        return runReport(
            kind = kind,
            target = inbound.replyTarget,
            event = event,
            scheduled = false,
        ).thenApply<Void> { null }
    }

    private fun runScheduleTick() {
        if (!running) return
        try {
            val now = LocalDate.now(clock)
            val hour = clock.instant().atZone(clock.zone).hour
            if (lastCleanupDate != now) {
                newsImage.cleanupExpiredImages()
                animeImage.cleanupExpiredImages()
                lastCleanupDate = now
            }
            ReportKind.entries.forEach { kind ->
                val report = configuration.snapshot().report(kind)
                report.enabledGroups.forEach { groupId ->
                    if (configuration.scheduledHour(kind, groupId) == hour) {
                        dispatchAutomatically(kind, groupId, now)
                    }
                }
            }
        } catch (failure: Throwable) {
            logFailure("Scheduled MIE News Reporter tick failed", failure)
        }
    }

    private fun dispatchAutomatically(kind: ReportKind, groupId: String, date: LocalDate) {
        val deduplicationKey = "${kind.name.lowercase()}:$groupId"
        if (dispatchLedger.wasSent(kind, groupId, date) || !automaticInFlight.add(deduplicationKey)) return

        val target = MessageTarget(MessageTargetType.GROUP, groupId)
        runReport(kind, target, event = null, scheduled = true).whenComplete { delivered, failure ->
            if (failure != null) {
                logFailure("Automatic ${kind.name.lowercase()} report failed for group $groupId", failure)
            } else if (delivered == true) {
                dispatchLedger.markSent(kind, groupId, date)
            }
            automaticInFlight.remove(deduplicationKey)
        }
    }

    /** Fetches once and sends an image; false means a configured failure notice was used. */
    private fun runReport(
        kind: ReportKind,
        target: MessageTarget,
        event: PluginEvent?,
        scheduled: Boolean,
    ): CompletionStage<Boolean> {
        val result = CompletableFuture<Boolean>()
        val provider = if (kind == ReportKind.NEWS) newsImage else animeImage
        val imageFuture = try {
            requireNotNull(provider.todayImage()) { "daily image provider returned no stage" }
        } catch (failure: Throwable) {
            completeReportFailure(result, kind, target, event, scheduled, failure)
            return result
        }

        imageFuture.whenComplete { bytes, fetchFailure ->
            if (fetchFailure != null) {
                completeReportFailure(result, kind, target, event, scheduled, fetchFailure)
                return@whenComplete
            }
            val sendFuture = try {
                sendImage(kind, target, event, bytes)
            } catch (failure: Throwable) {
                completeReportFailure(result, kind, target, event, scheduled, failure)
                return@whenComplete
            }
            sendFuture.whenComplete { _, sendFailure ->
                if (sendFailure == null) {
                    result.complete(true)
                } else {
                    completeReportFailure(result, kind, target, event, scheduled, sendFailure)
                }
            }
        }
        return result
    }

    private fun completeReportFailure(
        result: CompletableFuture<Boolean>,
        kind: ReportKind,
        target: MessageTarget,
        event: PluginEvent?,
        scheduled: Boolean,
        failure: Throwable,
    ) {
        logFailure("${kind.name.lowercase()} image preparation failed", failure)
        if (scheduled) {
            result.complete(false)
            return
        }
        sendFailureText(kind, target, event).whenComplete { _, reminderFailure ->
            if (reminderFailure != null) logFailure("Could not send ${kind.name.lowercase()} failure notice", reminderFailure)
            result.complete(false)
        }
    }

    private fun sendImage(
        kind: ReportKind,
        target: MessageTarget,
        event: PluginEvent?,
        bytes: ByteArray,
    ): CompletionStage<Void> {
        val date = LocalDate.now(clock).toString().replace("-", "")
        val upload = MediaUpload(
            kind = MediaKind.IMAGE,
            fileName = "mienr-${kind.name.lowercase()}-$date.png",
            contentType = "image/png",
            data = bytes,
        )
        val staged = requireNotNull(context.mediaService.stage(upload)) { "media service returned no stage" }
        val message = staged.thenCompose { media ->
            val outbound = StagedMediaMessage(
                target = target,
                media = media,
                replyMessageId = event?.message?.messageId,
                replyEventId = event?.message?.eventId,
                deduplicationKey = event?.let { "mienr:${kind.name.lowercase()}:reply:${it.id}" },
                sourceEventId = event?.id,
            )
            enqueueMedia(outbound, event?.message?.messageId)
        }
        return message.thenApply<Void> { null }
    }

    private fun sendFailureText(
        kind: ReportKind,
        target: MessageTarget,
        event: PluginEvent?,
    ): CompletionStage<Void> = sendText(
        target = target,
        content = configuration.snapshot().report(kind).failureMessage,
        event = event,
        referenceTag = "failure-${kind.name.lowercase()}",
    )

    private fun sendQuotedText(event: PluginEvent, content: String, tag: String): CompletionStage<Void> {
        val inbound = event.message ?: return completedVoid()
        return sendText(inbound.replyTarget, content, event, tag)
    }

    private fun sendText(
        target: MessageTarget,
        content: String,
        event: PluginEvent?,
        referenceTag: String,
    ): CompletionStage<Void> {
        val inbound = event?.message
        val message = TextMessage(
            target = target,
            content = content,
            replyMessageId = inbound?.messageId,
            replyEventId = inbound?.eventId,
            deduplicationKey = event?.let { "mienr:text:$referenceTag:${it.id}" },
            sourceEventId = event?.id,
        )
        return enqueueText(message, inbound?.messageId)
    }

    private fun enqueueText(message: TextMessage, referenceMessageId: String?): CompletionStage<Void> {
        val options = optionsFor(referenceMessageId)
        return try {
            context.base.messageSender.enqueue(message, options)
                .handle<Void> { _, failure ->
                    if (failure != null) logFailure("Text message enqueue failed", failure)
                    null
                }
        } catch (failure: Throwable) {
            logFailure("Text message enqueue failed", failure)
            completedVoid()
        }
    }

    private fun enqueueMedia(message: StagedMediaMessage, referenceMessageId: String?): CompletionStage<Void> {
        return context.mediaService.enqueue(message, optionsFor(referenceMessageId))
            .thenApply<Void> { null }
    }

    private fun optionsFor(referenceMessageId: String?): MessageSendOptions =
        referenceMessageId?.takeIf(String::isNotBlank)?.let { MessageSendOptions(MessageReference(it)) }
            ?: MessageSendOptions()

    private fun isAdministrator(message: InboundMessage): Boolean =
        message.memberRole == GroupMemberRole.ADMIN || message.memberRole == GroupMemberRole.OWNER

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        scheduleTask?.close()
        scheduleTask = null
        subscriptions.forEach(EventSubscription::close)
        subscriptions.clear()
        automaticInFlight.clear()
    }

    private fun logFailure(message: String, failure: Throwable) {
        context.base.logger.error(message, failure)
    }

    private fun ReportKind.label(): String = if (this == ReportKind.NEWS) "今日新闻" else "今日番剧"

    private companion object {
        val MESSAGE_EVENT_TYPES = setOf(
            "GROUP_MESSAGE_CREATE",
            "GROUP_AT_MESSAGE_CREATE",
            "MESSAGE_CREATE",
            "AT_MESSAGE_CREATE",
        )
        const val PERMISSION_MESSAGE = "只有群管理员或群主可以使用该管理指令。"
        const val CONFIG_FAILURE_MESSAGE = "配置更新失败，请稍后重试。"

        fun completedVoid(): CompletionStage<Void> = CompletableFuture.completedFuture(null)
    }
}
