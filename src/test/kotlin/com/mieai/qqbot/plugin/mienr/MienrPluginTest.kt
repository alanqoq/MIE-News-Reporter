package com.mieai.qqbot.plugin.mienr

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.plugin.api.GroupMemberRole
import com.mieai.qqbot.plugin.api.InboundMessage
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import com.mieai.qqbot.plugin.testkit.PluginTestContext
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.ServiceLoader
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MienrPluginTest {
    @Test
    fun `service loader discovers a startable mienr factory`() {
        val factory = ServiceLoader.load(BotPluginFactory::class.java)
            .single { it.pluginId == "mienr" }
        val fixture = fixture(configuration(), "mienr")

        fixture.use {
            val plugin = factory.create(fixture.context)
            plugin.start()
            assertEquals(setOf("mienr-commands"), fixture.events.handlerIds())
            plugin.stop()
            assertTrue(fixture.events.handlerIds().isEmpty())
        }
    }

    @Test
    fun `only administrators can toggle and set group time`() {
        val fixture = fixture(configuration())
        fixture.use {
            val store = MienrConfigurationStore.open(fixture.context.configurationFile, fixture.context.configuration.content)
            val plugin = MienrPlugin(fixture.context, store, testClock(), imageProvider(), imageProvider())
            plugin.start()

            emit(fixture, "/mienr news", GroupMemberRole.MEMBER)
            assertThatLastText(fixture, "只有群管理员或群主")
            assertFalse(store.isEnabled(ReportKind.NEWS, GROUP_ID))

            emit(fixture, "/mienr news", GroupMemberRole.ADMIN, "message-admin")
            assertTrue(store.isEnabled(ReportKind.NEWS, GROUP_ID))
            assertEquals("message-admin", fixture.messages.textSendOptions().last().messageReference?.messageId)

            emit(fixture, "/mienr timenews 07", GroupMemberRole.OWNER, "message-owner")
            assertEquals(7, store.scheduledHour(ReportKind.NEWS, GROUP_ID))

            emit(fixture, "/mienr news", GroupMemberRole.OWNER, "message-close")
            assertFalse(store.isEnabled(ReportKind.NEWS, GROUP_ID))
            assertTrue(store.snapshot().news.groupHours.isEmpty())
            plugin.stop()
        }
    }

    @Test
    fun `configured aliases execute through the plugin entry point`() {
        val base = MienrConfiguration.defaults()
        val configured = base.copy(
            commands = base.commands.copy(
                aliases = base.commands.aliases.copy(
                    mienr = setOf("日报"),
                    anime = setOf("开启今日番剧"),
                    timeanime = setOf("设置番剧时间"),
                ),
            ),
        )
        val fixture = fixture(MienrConfiguration.render(configured))
        fixture.use {
            val store = MienrConfigurationStore.open(fixture.context.configurationFile, fixture.context.configuration.content)
            val plugin = MienrPlugin(fixture.context, store, testClock(), imageProvider(), imageProvider())
            plugin.start()

            emit(fixture, "/开启今日番剧", GroupMemberRole.ADMIN)
            assertTrue(store.isEnabled(ReportKind.ANIME, GROUP_ID))

            emit(fixture, "/日报 设置番剧时间 06", GroupMemberRole.OWNER)
            assertEquals(6, store.scheduledHour(ReportKind.ANIME, GROUP_ID))
            plugin.stop()
        }
    }

    @Test
    fun `disabled get commands quote the matching configured reminder`() {
        val fixture = fixture(configuration())
        fixture.use {
            val store = MienrConfigurationStore.open(fixture.context.configurationFile, fixture.context.configuration.content)
            val plugin = MienrPlugin(fixture.context, store, testClock(), imageProvider(), imageProvider())
            plugin.start()

            emit(fixture, "/mienr getnews", GroupMemberRole.MEMBER, "news-request")
            emit(fixture, "/mienr getanime", GroupMemberRole.MEMBER, "anime-request")

            assertEquals(2, fixture.messages.textMessages().size)
            assertTrue(fixture.messages.textMessages()[0].content.contains("新闻"))
            assertTrue(fixture.messages.textMessages()[1].content.contains("番剧"))
            assertEquals("news-request", fixture.messages.textSendOptions()[0].messageReference?.messageId)
            assertEquals("anime-request", fixture.messages.textSendOptions()[1].messageReference?.messageId)
            assertTrue(fixture.media.stagedMessages().isEmpty())
            plugin.stop()
        }
    }

    @Test
    fun `enabled get commands stage and send the correct image with an explicit quote`() {
        val base = MienrConfiguration.defaults()
        val configured = base.copy(
            news = base.news.copy(enabledGroups = setOf(GROUP_ID)),
            anime = base.anime.copy(enabledGroups = setOf(GROUP_ID)),
        )
        val fixture = fixture(MienrConfiguration.render(configured))
        fixture.use {
            val store = MienrConfigurationStore.open(fixture.context.configurationFile, fixture.context.configuration.content)
            val newsBytes = pngBytes(0x11)
            val animeBytes = pngBytes(0x22)
            val plugin = MienrPlugin(
                fixture.context,
                store,
                testClock(),
                imageProvider(newsBytes),
                imageProvider(animeBytes),
            )
            plugin.start()

            emit(fixture, "/mienr getnews", GroupMemberRole.MEMBER, "news-message")
            emit(fixture, "/mienr getanime", GroupMemberRole.MEMBER, "anime-message")

            assertEquals(2, fixture.media.stagedMessages().size)
            assertEquals(newsBytes.toList(), fixture.media.uploads()[0].data.toList())
            assertEquals(animeBytes.toList(), fixture.media.uploads()[1].data.toList())
            assertEquals("news-message", fixture.media.stagedMessageSendOptions()[0].messageReference?.messageId)
            assertEquals("anime-message", fixture.media.stagedMessageSendOptions()[1].messageReference?.messageId)
            plugin.stop()
        }
    }

    @Test
    fun `scheduler sends an enabled report once per group and hour`() {
        val base = MienrConfiguration.defaults()
        val configured = base.copy(
            news = base.news.copy(enabledGroups = setOf(GROUP_ID), defaultHour = 8),
        )
        val fixture = fixture(MienrConfiguration.render(configured))
        fixture.use {
            val store = MienrConfigurationStore.open(fixture.context.configurationFile, fixture.context.configuration.content)
            val plugin = MienrPlugin(fixture.context, store, testClock(), imageProvider(), imageProvider())
            plugin.start()

            fixture.scheduler.runReady()
            fixture.scheduler.runReady()

            assertEquals(1, fixture.media.stagedMessages().size)
            assertEquals(MessageTarget(MessageTargetType.GROUP, GROUP_ID), fixture.media.stagedMessages().single().target)
            assertTrue(fixture.messages.textMessages().isEmpty())
            plugin.stop()

            val restarted = MienrPlugin(fixture.context, store, testClock(), imageProvider(), imageProvider())
            restarted.start()
            fixture.scheduler.runReady()
            assertEquals(1, fixture.media.stagedMessages().size)
            restarted.stop()
        }
    }

    private fun fixture(config: String, pluginId: String = "mienr-test-${UUID.randomUUID()}"): PluginTestContext =
        PluginTestContext(pluginId, config, "config.yml")

    private fun configuration(): String = MienrConfiguration.render(MienrConfiguration.defaults())

    private fun testClock(): Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"))

    private fun imageProvider(bytes: ByteArray = pngBytes(0x33)): DailyImageProvider =
        DailyImageProvider { CompletableFuture.completedFuture(bytes.clone()) }

    private fun emit(fixture: PluginTestContext, content: String, role: GroupMemberRole, messageId: String = "message-1") {
        fixture.events.emit(event(fixture, content, role, messageId)).toCompletableFuture().join()
    }

    private fun event(
        fixture: PluginTestContext,
        content: String,
        role: GroupMemberRole,
        messageId: String,
    ): PluginEvent = PluginEvent(
        id = UUID.randomUUID(),
        botId = fixture.context.base.botId,
        environment = BotEnvironment.SANDBOX,
        eventType = "GROUP_MESSAGE_CREATE",
        platformEventId = "platform-${UUID.randomUUID()}",
        rawPayload = "{}",
        receivedAt = Instant.parse("2026-01-01T00:00:00Z"),
        message = InboundMessage(
            replyTarget = MessageTarget(MessageTargetType.GROUP, GROUP_ID),
            messageId = messageId,
            eventId = "event-$messageId",
            authorId = "user-1",
            content = content,
            memberRole = role,
        ),
    )

    private fun assertThatLastText(fixture: PluginTestContext, fragment: String) {
        assertTrue(fixture.messages.textMessages().last().content.contains(fragment))
    }

    private companion object {
        const val GROUP_ID = "group-1"

        fun pngBytes(marker: Int): ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            marker.toByte(),
        )
    }
}
