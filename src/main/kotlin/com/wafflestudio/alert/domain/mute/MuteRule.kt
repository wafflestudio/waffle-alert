package com.wafflestudio.alert.domain.mute

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 채널 단위 mute 한 건. [keyword]가 비어 있으면 채널 전체, 아니면 title에 그 키워드가 들어간 알림만 막는다. */
@Entity
@Table(name = "mute_rules")
class MuteRule(
    @Column(nullable = false)
    val channelId: String,
    @Column(nullable = false)
    val keyword: String,
    @Column(nullable = false)
    var expiresAt: Instant,
    @Column(nullable = false)
    val createdBy: String,
    @Column(nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    var endedAt: Instant? = null

    @Enumerated(EnumType.STRING)
    var endReason: EndReason? = null

    var endedBy: String? = null

    val isChannelWide: Boolean
        get() = keyword.isEmpty()

    /** title이 이 mute에 걸리는지. 호출자가 title을 소문자로 넘긴다. */
    fun matches(lowercaseTitle: String): Boolean = isChannelWide || lowercaseTitle.contains(keyword)

    enum class EndReason {
        UNMUTED,
        EXPIRED,
    }
}
