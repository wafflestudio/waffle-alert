package com.wafflestudio.alert.domain.mute

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class MuteService(
    private val repository: MuteRuleRepository,
    private val clock: Clock,
) {
    /** 같은 채널에 같은 키워드로 이미 활성 mute가 있으면 새로 만들지 않고 만료 시각만 갱신한다. */
    @Transactional
    fun mute(
        channelId: String,
        keyword: String?,
        duration: Duration,
        userId: String,
    ): MuteRule {
        val normalized = normalize(keyword)
        val now = clock.instant()
        val expiresAt = now.plus(duration)
        repository
            .findActive(channelId, now)
            .firstOrNull { it.keyword == normalized }
            ?.let {
                it.expiresAt = expiresAt
                return it
            }
        return repository.save(MuteRule(channelId, normalized, expiresAt, userId, now))
    }

    @Transactional(readOnly = true)
    fun activeMutes(channelId: String): List<MuteRule> = repository.findActive(channelId, clock.instant()).sortedBy { it.expiresAt }

    /** title이 걸리는 활성 mute가 있으면 그중 하나를, 없으면 null. */
    @Transactional(readOnly = true)
    fun findMatching(
        channelId: String,
        title: String,
    ): MuteRule? {
        val lowercaseTitle = title.lowercase()
        return repository.findActive(channelId, clock.instant()).firstOrNull { it.matches(lowercaseTitle) }
    }

    /** 이 채널의 활성 mute를 해제한다. 다른 채널 것이거나 이미 끝났거나 다른 파드가 먼저 해제했으면 null. */
    @Transactional
    fun unmute(
        channelId: String,
        ruleId: Long,
        userId: String,
    ): MuteRule? {
        val now = clock.instant()
        val rule =
            repository
                .findByIdOrNull(ruleId)
                ?.takeIf { it.channelId == channelId && it.endedAt == null && it.expiresAt.isAfter(now) }
                ?: return null
        return rule.takeIf { repository.end(ruleId, now, MuteRule.EndReason.UNMUTED, userId) == 1 }
    }

    /** 만료된 mute를 끝내고, 이 호출이 선점한 것만 돌려준다 (호출자가 만료 공지를 보낸다). */
    @Transactional
    fun claimExpired(): List<MuteRule> {
        val now = clock.instant()
        return repository.findExpired(now).filter { repository.end(it.id, now, MuteRule.EndReason.EXPIRED, null) == 1 }
    }

    private fun normalize(keyword: String?): String = keyword?.trim()?.lowercase().orEmpty()
}
