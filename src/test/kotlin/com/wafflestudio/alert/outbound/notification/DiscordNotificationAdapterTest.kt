package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.Severity
import com.wafflestudio.alert.outbound.notification.routing.RoutingPolicy
import com.wafflestudio.alert.outbound.notification.routing.TeamMappingConfig
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
            channelIds = mapOf("prometheus-alert" to "channel-1", "team-infra-alert" to "channel-2")
        }
    private val routingPolicy =
        RoutingPolicy(
            TeamMappingConfig().apply {
                namespaceToChannel = mapOf("waffle-alert-prod" to "team-infra-alert")
            },
        )
    private val lokiClient = mockk<LokiClient>()
    private val adapter =
        spyk(DiscordNotificationAdapter(mockk<RestClient>(relaxed = true), discordProperties, lokiClient, routingPolicy)) {
            every { sendMessage(any(), any()) } returns Unit
        }

    @Test
    fun `namespace가 team-mapping에 있으면 alert 종류와 무관하게 해당 팀 채널로 보낸다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "waffle-alert-prod")

        adapter.notify(event)

        verify(exactly = 0) { lokiClient.fetchLogLines(any(), any()) }
        verify { adapter.sendMessage("channel-2", any()) }
    }

    @Test
    fun `namespace가 team-mapping에 없으면 source 기준 기본 채널로 폴백한다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-prod")

        adapter.notify(event)

        verify(exactly = 0) { lokiClient.fetchLogLines(any(), any()) }
        verify { adapter.sendMessage("channel-1", match { !it.contains("```") }) }
    }

    @Test
    fun `ApplicationErrorLog면 namespace 기준으로 Loki를 조회해 로그 원문과 Grafana 링크를 포함한다`() {
        val event = baseEvent(ruleName = "ApplicationErrorLog", namespace = "siksha-prod")
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
        val event = baseEvent(ruleName = "ApplicationErrorLog", namespace = "siksha-prod")
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

    private fun baseEvent(
        ruleName: String,
        namespace: String,
    ) = AlertEvent(
        source = AlertSource.ALERTMANAGER,
        status = AlertStatus.FIRING,
        severity = Severity.WARNING,
        fingerprint = "fp1",
        ruleName = ruleName,
        title = "$namespace error log detected",
        service = namespace,
        observedAt = Instant.parse("2026-08-15T00:00:00Z"),
    )
}
