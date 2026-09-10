package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.Severity
import com.wafflestudio.alert.outbound.notification.routing.RoutingPolicy
import com.wafflestudio.alert.outbound.notification.routing.TeamChannels
import com.wafflestudio.alert.outbound.notification.routing.TeamMappingConfig
import com.wafflestudio.alert.source.loki.LokiClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant

class DiscordNotificationAdapterTest {
    private val discordProperties =
        DiscordProperties().apply {
            channelIds =
                mapOf(
                    "prometheus-alert" to "channel-1",
                    "team-infra-alert" to "channel-2",
                    "siksha-app-alert" to "channel-3",
                    "siksha-infra-alert" to "channel-4",
                    "oci-monitoring" to "channel-5",
                )
        }
    private val routingPolicy =
        RoutingPolicy(
            TeamMappingConfig().apply {
                namespaces =
                    mapOf(
                        "waffle-alert-prod" to
                            TeamChannels().apply {
                                app = "team-infra-alert"
                                infra = "team-infra-alert"
                            },
                        "siksha-prod" to
                            TeamChannels().apply {
                                app = "siksha-app-alert"
                                infra = "siksha-infra-alert"
                            },
                    )
            },
        )
    private val lokiClient = mockk<LokiClient>()
    private val adapter =
        spyk(DiscordNotificationAdapter(mockk<RestClient>(relaxed = true), discordProperties, lokiClient, routingPolicy)) {
            every { sendMessage(any(), any()) } returns true
        }

    @Test
    fun `워크로드 alert는 namespace의 infra 채널로 보낸다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-prod")

        adapter.notify(event)

        verify(exactly = 0) { lokiClient.fetchLogLines(any(), any()) }
        verify { adapter.sendMessage("channel-4", any()) }
    }

    @Test
    fun `namespace가 team-mapping에 없으면 source 기준 기본 채널로 폴백한다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "unmapped-prod")

        adapter.notify(event)

        verify(exactly = 0) { lokiClient.fetchLogLines(any(), any()) }
        verify { adapter.sendMessage("channel-1", match { !it.contains("```") }) }
    }

    @Test
    fun `ApplicationErrorLog면 namespace의 app 채널로 보내고 Loki를 조회해 로그 원문과 Grafana 링크를 포함한다`() {
        val event = baseEvent(ruleName = "ApplicationErrorLog", namespace = "siksha-prod")
        every { lokiClient.fetchLogLines("siksha-prod", event.observedAt) } returns
            listOf("2026-08-15 ERROR something broke")
        every { lokiClient.grafanaExploreUrl("siksha-prod", event.observedAt) } returns
            "https://grafana.wafflestudio.com/explore?panes=..."

        adapter.notify(event)

        verify {
            adapter.sendMessage(
                "channel-3",
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
                "channel-3",
                match { !it.contains("```") && it.contains("Grafana에서 전체 로그 보기") },
            )
        }
    }

    @Test
    fun `ApplicationErrorLog는 namespace 매핑이 없어도 prometheus-alert가 아니라 team-infra-alert로 보낸다`() {
        val event = baseEvent(ruleName = "ApplicationErrorLog", namespace = "argocd")
        every { lokiClient.fetchLogLines(any(), any()) } returns emptyList()
        every { lokiClient.grafanaExploreUrl(any(), any()) } returns null

        adapter.notify(event)

        verify { adapter.sendMessage("channel-2", any()) }
    }

    @Test
    fun `ApplicationErrorLog의 RESOLVED는 전송하지 않는다`() {
        val event = baseEvent(ruleName = "ApplicationErrorLog", namespace = "siksha-prod", status = AlertStatus.RESOLVED)

        val result = adapter.notify(event)

        assertTrue(result)
        verify(exactly = 0) { lokiClient.fetchLogLines(any(), any()) }
        verify(exactly = 0) { adapter.sendMessage(any(), any()) }
    }

    @Test
    fun `워크로드 alert의 RESOLVED는 그대로 전송한다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-prod", status = AlertStatus.RESOLVED)

        adapter.notify(event)

        verify { adapter.sendMessage("channel-4", match { it.contains("RESOLVED") }) }
    }

    @Test
    fun `OCI Monitoring alert에 실제 metric 조회 범위를 KST로 표시한다`() {
        val event =
            baseEvent(ruleName = "cpu-utilization-high", namespace = "OCI-DB").copy(
                source = AlertSource.OCI_MONITORING,
                labels =
                    mapOf(
                        "queryStartTime" to "2026-07-12T00:49:00Z",
                        "queryEndTime" to "2026-07-12T01:05:00Z",
                    ),
            )

        adapter.notify(event)

        verify {
            adapter.sendMessage(
                "channel-5",
                match { it.contains("조회 범위: 2026-07-12 09:49:00 ~ 2026-07-12 10:05:00 KST") },
            )
        }
    }

    private fun baseEvent(
        ruleName: String,
        namespace: String,
        status: AlertStatus = AlertStatus.FIRING,
    ) = AlertEvent(
        source = AlertSource.ALERTMANAGER,
        status = status,
        severity = Severity.WARNING,
        fingerprint = "fp1",
        ruleName = ruleName,
        title = "$namespace error log detected",
        service = namespace,
        observedAt = Instant.parse("2026-08-15T00:00:00Z"),
    )
}
