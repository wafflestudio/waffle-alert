package com.wafflestudio.alert.inbound.discord

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.domain.mute.MuteService
import com.wafflestudio.alert.outbound.notification.DiscordNotificationAdapter
import org.springframework.stereotype.Component

/**
 * /mute, /unmute의 실제 처리. JDA 이벤트와 떨어뜨려 두어서 JDA 없이 테스트할 수 있다.
 * 채널 공지는 알림과 같은 경로(봇 메시지)로 보낸다 - 슬래시 명령어 응답으로 공개하면
 * Discord가 입력자를 `{user} used /mute`로 붙이기 때문이다.
 */
@Component
class MuteCommandHandler(
    private val muteService: MuteService,
    private val discordProperties: DiscordProperties,
    private val notifier: DiscordNotificationAdapter,
) {
    /** discord.channel-ids에 등록된 알림 채널에서만 명령어를 받는다. */
    fun isAlertChannel(channelId: String): Boolean = channelId in discordProperties.channelIds.values

    fun mute(
        channelId: String,
        userId: String,
        duration: MuteDuration,
        keyword: String?,
    ) {
        val rule = muteService.mute(channelId, keyword, duration.duration, userId)
        notifier.sendMessage(channelId, MuteMessages.muted(rule))
    }

    /** @return 해제했으면 true. 이미 풀렸거나 다른 채널 것이거나 target이 id가 아니면 false. */
    fun unmute(
        channelId: String,
        userId: String,
        target: String?,
    ): Boolean {
        val ruleId = target?.toLongOrNull() ?: return false
        val rule = muteService.unmute(channelId, ruleId, userId) ?: return false
        notifier.sendMessage(channelId, MuteMessages.unmuted(rule))
        return true
    }

    /** /unmute target 자동완성. 이름은 사람이 읽는 설명, 값은 mute id. Discord 상한은 25개다. */
    fun unmuteChoices(
        channelId: String,
        typed: String,
    ): List<Pair<String, String>> =
        muteService
            .activeMutes(channelId)
            .map { MuteMessages.choiceLabel(it) to it.id.toString() }
            .filter { (label, _) -> label.contains(typed.trim(), ignoreCase = true) }
            .take(MAX_CHOICES)

    /** 만료된 mute를 선점해서 각 채널에 만료 공지를 보낸다. */
    fun announceExpired() {
        muteService.claimExpired().forEach { notifier.sendMessage(it.channelId, MuteMessages.expired(it)) }
    }

    private companion object {
        const val MAX_CHOICES = 25
    }
}
