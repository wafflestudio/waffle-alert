package com.wafflestudio.alert.inbound.discord

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.domain.mute.MuteRule
import com.wafflestudio.alert.domain.mute.MuteService
import com.wafflestudio.alert.outbound.notification.DiscordNotificationAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MuteCommandHandlerTest {
    private val muteService = mockk<MuteService>()
    private val notifier = mockk<DiscordNotificationAdapter> { every { sendMessage(any(), any()) } returns true }
    private val handler =
        MuteCommandHandler(
            muteService,
            DiscordProperties().apply { channelIds = mapOf("hangsha-infra-alert" to CHANNEL) },
            notifier,
        )

    @Test
    fun `discord channel-ids에 등록된 채널만 알림 채널로 본다`() {
        assertTrue(handler.isAlertChannel(CHANNEL))
        assertFalse(handler.isAlertChannel("999"))
    }

    @Test
    fun `mute를 걸면 채널에 키워드와 만료 시각을 KST로 알린다`() {
        every { muteService.mute(CHANNEL, "Hangsha-API", Duration.ofHours(1), USER) } returns rule("hangsha-api")

        handler.mute(CHANNEL, USER, MuteDuration.HOUR_1, "Hangsha-API")

        verify { notifier.sendMessage(CHANNEL, "Muted \"hangsha-api\" until 09-28 13:21 KST") }
    }

    @Test
    fun `키워드 없이 걸면 all alerts로 알린다`() {
        every { muteService.mute(CHANNEL, null, Duration.ofDays(7), USER) } returns rule("")

        handler.mute(CHANNEL, USER, MuteDuration.DAYS_7, null)

        verify { notifier.sendMessage(CHANNEL, "Muted all alerts until 09-28 13:21 KST") }
    }

    @Test
    fun `키워드의 마크다운 문자는 이스케이프한다`() {
        every { muteService.mute(any(), any(), any(), any()) } returns rule("a_b")

        handler.mute(CHANNEL, USER, MuteDuration.HOUR_1, "a_b")

        verify { notifier.sendMessage(CHANNEL, "Muted \"a\\_b\" until 09-28 13:21 KST") }
    }

    @Test
    fun `unmute하면 채널에 알리고 true를 돌려준다`() {
        every { muteService.unmute(CHANNEL, 3, USER) } returns rule("hangsha-api")

        assertTrue(handler.unmute(CHANNEL, USER, "3"))
        verify { notifier.sendMessage(CHANNEL, "Unmuted \"hangsha-api\"") }
    }

    @Test
    fun `해제할 mute가 없거나 target이 id가 아니면 알리지 않는다`() {
        every { muteService.unmute(CHANNEL, 3, USER) } returns null

        assertFalse(handler.unmute(CHANNEL, USER, "3"))
        assertFalse(handler.unmute(CHANNEL, USER, "hangsha-api"))
        assertFalse(handler.unmute(CHANNEL, USER, null))
        verify(exactly = 0) { notifier.sendMessage(any(), any()) }
    }

    @Test
    fun `unmute 자동완성은 이 채널의 활성 mute를 입력값으로 걸러서 보여준다`() {
        every { muteService.activeMutes(CHANNEL) } returns listOf(rule("", id = 1), rule("hangsha-api", id = 2))

        assertEquals(
            listOf("all alerts until 09-28 13:21 KST" to "1", "\"hangsha-api\" until 09-28 13:21 KST" to "2"),
            handler.unmuteChoices(CHANNEL, ""),
        )
        assertEquals(listOf("2"), handler.unmuteChoices(CHANNEL, " API ").map { it.second })
    }

    @Test
    fun `만료된 mute는 각자의 채널에 만료 공지를 보낸다`() {
        every { muteService.claimExpired() } returns listOf(rule("hangsha-api"), rule("", channelId = OTHER_CHANNEL))

        handler.announceExpired()

        verify { notifier.sendMessage(CHANNEL, "Mute expired: \"hangsha-api\"") }
        verify { notifier.sendMessage(OTHER_CHANNEL, "Mute expired: all alerts") }
    }

    @Test
    fun `duration 선택지는 30m부터 7d까지다`() {
        assertEquals(listOf("30m", "1h", "6h", "24h", "7d"), MuteDuration.entries.map { it.label })
        assertEquals(MuteDuration.HOURS_24, MuteDuration.fromLabel("24h"))
        assertEquals(null, MuteDuration.fromLabel("forever"))
    }

    @Test
    fun `명령어는 mute와 unmute 두 개이고 unmute target은 자동완성이다`() {
        val commands = MuteCommandListener.COMMANDS.associateBy { it.name }

        assertEquals(setOf("mute", "unmute"), commands.keys)
        val mute = commands.getValue("mute").options.associateBy { it.name }
        assertTrue(mute.getValue("duration").isRequired)
        assertFalse(mute.getValue("keyword").isRequired)
        assertEquals(MuteDuration.entries.map { it.label }, mute.getValue("duration").choices.map { it.asString })
        assertTrue(
            commands
                .getValue("unmute")
                .options
                .single()
                .isAutoComplete,
        )
    }

    private fun rule(
        keyword: String,
        id: Long = 0,
        channelId: String = CHANNEL,
    ): MuteRule =
        MuteRule(channelId, keyword, Instant.parse("2026-09-28T04:21:00Z"), USER, Instant.parse("2026-09-28T03:21:00Z")).also {
            if (id != 0L) {
                MuteRule::class.java
                    .getDeclaredField("id")
                    .apply { isAccessible = true }
                    .set(it, id)
            }
        }

    private companion object {
        const val CHANNEL = "1546754949550047252"
        const val OTHER_CHANNEL = "1532713920232554646"
        const val USER = "user-1"
    }
}
