package com.wafflestudio.alert.domain.mute

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface MuteRuleRepository : JpaRepository<MuteRule, Long> {
    @Query("select r from MuteRule r where r.channelId = :channelId and r.endedAt is null and r.expiresAt > :now")
    fun findActive(
        channelId: String,
        now: Instant,
    ): List<MuteRule>

    @Query("select r from MuteRule r where r.endedAt is null and r.expiresAt <= :now")
    fun findExpired(now: Instant): List<MuteRule>

    /**
     * 아직 끝나지 않은 mute만 끝낸다. 반환값 1이면 이 호출이 선점한 것이다. 배포 중 파드가 2개일 때
     * 해제/만료 공지가 두 번 나가지 않게 하는 장치다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update MuteRule r set r.endedAt = :now, r.endReason = :reason, r.endedBy = :endedBy " +
            "where r.id = :id and r.endedAt is null",
    )
    fun end(
        id: Long,
        now: Instant,
        reason: MuteRule.EndReason,
        endedBy: String?,
    ): Int
}
