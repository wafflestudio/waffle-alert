package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.config.MessageFormatProperties
import com.wafflestudio.alert.config.MetaField
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
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
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
                    "oci-cost" to "channel-6",
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
                        // dev는 app 채널만 매핑한다(infra는 이번 범위 밖) - 실제 채널이
                        // 아직 없어 discord.channel-ids에는 키를 안 넣은 상태를 재현한다.
                        "siksha-dev" to
                            TeamChannels().apply {
                                app = "siksha-dev-app-alert"
                            },
                    )
            },
        )
    private val messageFormatProperties =
        MessageFormatProperties().apply {
            sources =
                mapOf(
                    AlertSource.OCI_MONITORING to listOf(MetaField.SEVERITY),
                    AlertSource.OCI_COST to listOf(MetaField.SEVERITY),
                )
        }
    private val lokiClient = mockk<LokiClient>()
    private val sentContent = slot<String>()
    private val adapter =
        spyk(
            DiscordNotificationAdapter(
                mockk<RestClient>(relaxed = true),
                discordProperties,
                lokiClient,
                routingPolicy,
                messageFormatProperties,
            ),
        ) {
            every { sendMessage(any(), capture(sentContent)) } returns true
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
    fun `메시지는 title과 description만으로 이뤄지고 이모지, status, 메타 줄은 붙지 않는다`() {
        val event =
            baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-prod").copy(
                title = "Pod siksha-prod/siksha-api-abc near memory limit",
                description = "siksha-prod/siksha-api-abc memory at 96.3% of limit for 30m.",
                resourceName = "siksha-api-abc",
            )

        adapter.notify(event)

        assertEquals(
            "Pod siksha-prod/siksha-api-abc near memory limit\n" +
                "siksha-prod/siksha-api-abc memory at 96.3% of limit for 30m.",
            sentContent.captured,
        )
    }

    @Test
    fun `team이 있어도 멘션을 붙이지 않는다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-prod").copy(team = "infra")

        adapter.notify(event)

        assertTrue(sentContent.captured.startsWith("siksha-prod error log detected"))
        assertTrue(!sentContent.captured.contains("<@&"))
    }

    @Test
    fun `description이 비어 있으면 빈 줄 없이 다음 요소가 이어진다`() {
        val event = baseEvent(ruleName = "ApplicationErrorLog", namespace = "siksha-prod").copy(description = "")
        every { lokiClient.fetchLogLines(any(), any()) } returns listOf("ERROR something broke")
        every { lokiClient.grafanaExploreUrl(any(), any()) } returns "https://grafana.wafflestudio.com/explore?x"

        adapter.notify(event)

        assertEquals(
            "siksha-prod error log detected\n" +
                "```\nERROR something broke\n```\n" +
                "🔗 [View full logs in Grafana](https://grafana.wafflestudio.com/explore?x)",
            sentContent.captured,
        )
    }

    @Test
    fun `title의 마크다운 문자는 이스케이프해서 서식이 적용되지 않는다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-prod").copy(title = "a*b_c `d`")

        adapter.notify(event)

        assertEquals("a\\*b\\_c \\`d\\`", sentContent.captured)
    }

    @Test
    fun `OCI Cost는 설정대로 title 다음 줄에 severity를 보여준다`() {
        val event =
            baseEvent(ruleName = "oci-cost-spike", namespace = "unused").copy(
                source = AlertSource.OCI_COST,
                severity = Severity.CRITICAL,
                title = "OCI 일일 비용 급증",
                description = "2026-09-23 비용 3.00 SGD (전일 1.00 SGD 대비 3.00배)",
                service = null,
                team = "infra",
            )

        adapter.notify(event)

        verify { adapter.sendMessage("channel-6", any()) }
        assertEquals(
            "OCI 일일 비용 급증\n" +
                "severity: CRITICAL\n" +
                "2026-09-23 비용 3.00 SGD (전일 1.00 SGD 대비 3.00배)",
            sentContent.captured,
        )
    }

    @Test
    fun `메타 필드는 설정 순서와 상관없이 severity, service, resource 순서로 보여준다`() {
        messageFormatProperties.sources =
            mapOf(AlertSource.ALERTMANAGER to listOf(MetaField.RESOURCE, MetaField.SEVERITY, MetaField.SERVICE))
        val event =
            baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-prod").copy(
                title = "t",
                resourceName = "siksha-api-abc",
            )

        adapter.notify(event)

        assertEquals("t\nseverity: WARNING · service: siksha-prod · resource: siksha-api-abc", sentContent.captured)
    }

    @Test
    fun `켠 메타 필드의 값이 모두 비어 있으면 메타 줄을 넣지 않는다`() {
        messageFormatProperties.sources = mapOf(AlertSource.ALERTMANAGER to listOf(MetaField.RESOURCE))
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-prod").copy(title = "t")

        adapter.notify(event)

        assertEquals("t", sentContent.captured)
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
                match { !it.contains("```") && it.contains("View full logs in Grafana") },
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
    fun `워크로드 alert의 RESOLVED도 전송하지 않는다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-prod", status = AlertStatus.RESOLVED)

        val result = adapter.notify(event)

        assertTrue(result)
        verify(exactly = 0) { adapter.sendMessage(any(), any()) }
    }

    @Test
    fun `dev namespace는 app 채널 키로 라우팅되지만 채널 ID가 없으면 조용히 skip한다`() {
        val event = baseEvent(ruleName = "ApplicationErrorLog", namespace = "siksha-dev")
        every { lokiClient.fetchLogLines(any(), any()) } returns emptyList()
        every { lokiClient.grafanaExploreUrl(any(), any()) } returns null

        val result = adapter.notify(event)

        assertTrue(!result)
        verify(exactly = 0) { adapter.sendMessage(any(), any()) }
    }

    @Test
    fun `dev namespace는 infra 매핑이 없어 워크로드 alert는 소스 기본 채널로 폴백한다`() {
        val event = baseEvent(ruleName = "PodMemoryLimitHigh", namespace = "siksha-dev")

        adapter.notify(event)

        verify { adapter.sendMessage("channel-1", any()) }
    }

    @Test
    fun `OCI Monitoring alert는 severity와 실제 metric 조회 범위를 KST로 표시한다`() {
        val event =
            baseEvent(ruleName = "cpu-utilization-high", namespace = "OCI-DB").copy(
                source = AlertSource.OCI_MONITORING,
                title = "MySQL CPU utilization high",
                description = "wafflestudio-mysql CPU utilization is 85.3% (threshold: 80.0%).",
                resourceName = "wafflestudio-mysql",
                labels =
                    mapOf(
                        "queryStartTime" to "2026-07-12T00:49:00Z",
                        "queryEndTime" to "2026-07-12T01:05:00Z",
                    ),
            )

        adapter.notify(event)

        verify { adapter.sendMessage("channel-5", any()) }
        assertEquals(
            "MySQL CPU utilization high\n" +
                "severity: WARNING\n" +
                "wafflestudio-mysql CPU utilization is 85.3% (threshold: 80.0%).\n" +
                "조회 범위: 2026-07-12 09:49:00 ~ 2026-07-12 10:05:00 KST",
            sentContent.captured,
        )
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
