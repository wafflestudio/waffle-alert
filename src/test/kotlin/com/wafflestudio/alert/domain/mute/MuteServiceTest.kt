package com.wafflestudio.alert.domain.mute

import com.wafflestudio.alert.MySQLTestContainerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 실제 MySQL(Testcontainers) + Flyway V3 스키마 위에서 검증한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MySQLTestContainerConfig::class)
class MuteServiceTest {
    @Autowired
    private lateinit var repository: MuteRuleRepository

    private val clock = MutableClock(Instant.parse("2026-09-28T03:00:00Z"))
    private lateinit var service: MuteService

    @BeforeEach
    fun setUp() {
        service = MuteService(repository, clock)
    }

    @Test
    fun `키워드는 앞뒤 공백을 지우고 소문자로 저장한다`() {
        val rule = service.mute(CHANNEL, "  Hangsha-API ", Duration.ofHours(1), USER)

        assertEquals("hangsha-api", rule.keyword)
        assertEquals(Instant.parse("2026-09-28T04:00:00Z"), rule.expiresAt)
    }

    @Test
    fun `title에 키워드가 들어간 알림만 대소문자 구분 없이 걸린다`() {
        service.mute(CHANNEL, "hangsha-api", Duration.ofHours(1), USER)

        assertNotNull(service.findMatching(CHANNEL, "[Pod Failed] hangsha-prod/HANGSHA-API-7f9c8d-x2k4p"))
        assertNull(service.findMatching(CHANNEL, "[Pod Failed] hangsha-prod/hangsha-crawler-29824020-6kzqn"))
    }

    @Test
    fun `키워드가 없으면 채널 전체가 걸린다`() {
        service.mute(CHANNEL, null, Duration.ofHours(1), USER)

        assertNotNull(service.findMatching(CHANNEL, "anything"))
        assertTrue(service.activeMutes(CHANNEL).single().isChannelWide)
    }

    @Test
    fun `다른 채널의 mute에는 걸리지 않는다`() {
        service.mute(OTHER_CHANNEL, null, Duration.ofHours(1), USER)

        assertNull(service.findMatching(CHANNEL, "anything"))
    }

    @Test
    fun `같은 키워드로 다시 걸면 새로 만들지 않고 만료 시각만 갱신한다`() {
        service.mute(CHANNEL, "hangsha-api", Duration.ofHours(1), USER)
        service.mute(CHANNEL, "HANGSHA-API", Duration.ofDays(1), USER)

        val active = service.activeMutes(CHANNEL)
        assertEquals(1, active.size)
        assertEquals(Instant.parse("2026-09-29T03:00:00Z"), active.single().expiresAt)
    }

    @Test
    fun `만료 시각이 지나면 더 이상 걸리지 않는다`() {
        service.mute(CHANNEL, "hangsha-api", Duration.ofMinutes(30), USER)

        clock.now = Instant.parse("2026-09-28T03:30:00Z")

        assertNull(service.findMatching(CHANNEL, "hangsha-api-abc"))
    }

    @Test
    fun `unmute하면 바로 풀리고 누가 풀었는지 남는다`() {
        val rule = service.mute(CHANNEL, "hangsha-api", Duration.ofHours(1), USER)

        val unmuted = service.unmute(CHANNEL, rule.id, "user-2")

        assertEquals("hangsha-api", unmuted?.keyword)
        assertNull(service.findMatching(CHANNEL, "hangsha-api-abc"))
        val saved = repository.findById(rule.id).get()
        assertEquals(MuteRule.EndReason.UNMUTED, saved.endReason)
        assertEquals("user-2", saved.endedBy)
    }

    @Test
    fun `다른 채널의 mute나 이미 풀린 mute는 unmute하지 않는다`() {
        val rule = service.mute(CHANNEL, "hangsha-api", Duration.ofHours(1), USER)

        assertNull(service.unmute(OTHER_CHANNEL, rule.id, USER))
        assertNotNull(service.unmute(CHANNEL, rule.id, USER))
        assertNull(service.unmute(CHANNEL, rule.id, USER))
    }

    @Test
    fun `만료된 mute는 한 번만 선점된다`() {
        service.mute(CHANNEL, "hangsha-api", Duration.ofMinutes(30), USER)
        service.mute(CHANNEL, "hangsha-crawler", Duration.ofHours(6), USER)
        clock.now = Instant.parse("2026-09-28T04:00:00Z")

        val first = service.claimExpired()
        val second = service.claimExpired()

        assertEquals(listOf("hangsha-api"), first.map { it.keyword })
        assertEquals(emptyList<MuteRule>(), second)
        assertEquals(listOf("hangsha-crawler"), service.activeMutes(CHANNEL).map { it.keyword })
    }

    class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        const val CHANNEL = "1546754949550047252"
        const val OTHER_CHANNEL = "1532713920232554646"
        const val USER = "user-1"
    }
}
