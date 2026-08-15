package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.Severity
import com.wafflestudio.alert.source.loki.LokiClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant

class DiscordNotificationAdapterTest {
    private val discordProperties =
        DiscordProperties().apply {
            channelIds = mapOf("prometheus-alert" to "channel-1")
        }
    private val lokiClient = mockk<LokiClient>()
    private val adapter =
        spyk(DiscordNotificationAdapter(mockk<RestClient>(relaxed = true), discordProperties, lokiClient)) {
            every { sendMessage(any(), any()) } returns Unit
        }

    @Test
    fun `ApplicationErrorLog가 아니면 Loki를 조회하지 않고 기존 포맷 그대로 보낸다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh")

        adapter.notify(event)

        verify(exactly = 0) { lokiClient.fetchLogLines(any(), any()) }
        verify { adapter.sendMessage("channel-1", match { !it.contains("```") }) }
    }

    @Test
    fun `ApplicationErrorLog면 namespace 기준으로 Loki를 조회해 로그 원문과 Grafana 링크를 포함한다`() {
        val event = baseEvent(ruleName = "ApplicationErrorLog")
        every { lokiClient.fetchLogLines("siksha-prod", event.observedAt) } returns
            listOf("2026-08-15 ERROR something broke")
        every { lokiClient.grafanaExploreUrl("siksha-prod", event.observedAt) } returns
            "https://grafana.wafflestudio.com/explore?panes=..."

        adapter.notify(event)

        verify {
            adapter.sendMessage(
                "channel-1",
                match {
                    it.contains("```") &&
                        it.contains("something broke") &&
                        it.contains("https://grafana.wafflestudio.com/explore")
                },
            )
        }
    }

    @Test
    fun `Loki 조회 결과가 비어있으면 코드블록 없이 링크만 붙인다`() {
        val event = baseEvent(ruleName = "ApplicationErrorLog")
        every { lokiClient.fetchLogLines("siksha-prod", event.observedAt) } returns emptyList()
        every { lokiClient.grafanaExploreUrl("siksha-prod", event.observedAt) } returns
            "https://grafana.wafflestudio.com/explore?panes=..."

        adapter.notify(event)

        verify {
            adapter.sendMessage(
                "channel-1",
                match { !it.contains("```") && it.contains("Grafana에서 전체 로그 보기") },
            )
        }
    }

    private fun baseEvent(ruleName: String) =
        AlertEvent(
            source = AlertSource.ALERTMANAGER,
            status = AlertStatus.FIRING,
            severity = Severity.WARNING,
            fingerprint = "fp1",
            ruleName = ruleName,
            title = "siksha-prod error log detected",
            service = "siksha-prod",
            observedAt = Instant.parse("2026-08-15T00:00:00Z"),
        )
}
