package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.outbound.notification.routing.DiscordMentionRole
import com.wafflestudio.alert.outbound.notification.routing.RoutingPolicy
import com.wafflestudio.alert.source.loki.LokiClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class DiscordNotificationAdapter(
    private val discordRestClient: RestClient,
    private val discordProperties: DiscordProperties,
    private val lokiClient: LokiClient,
    private val routingPolicy: RoutingPolicy,
) : NotificationPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun notify(event: AlertEvent): Boolean {
        // namespace가 alert.team-mapping.namespace-to-channel에 매핑돼 있으면 그 팀 채널로
        // 우선 보내고, 매핑이 없으면 기본 채널로 폴백한다.
        val channelKey = routingPolicy.channelKeyForNamespace(event.service) ?: defaultChannelKeyFor(event)
        val channelId = discordProperties.channelIds[channelKey]
        if (channelId.isNullOrBlank()) {
            log.warn("Discord channel not configured for channelKey={}, skip notify (fingerprint={})", channelKey, event.fingerprint)
            return false
        }

        return sendMessage(channelId, formatMessage(event))
    }

    /**
     * Loki 기반 alert(ApplicationErrorLog)는 namespace 매핑이 없으면 team-infra-alert로
     * 보낸다 - Loki 파이프라인 자체가 infra팀 소유이고, ApplicationErrorLog 룰이 team 매핑이
     * 안 된 시스템/미할당 네임스페이스(loki, argocd, external-secrets 등)에도 넓게 발동하는데
     * 이걸 레거시 Prometheus metric alert용 채널(prometheus-alert)에 섞으면 안 된다.
     * 그 외 alert(Prometheus metric, OCI 등)는 기존처럼 source 기준으로 보낸다.
     */
    private fun defaultChannelKeyFor(event: AlertEvent): String =
        if (event.ruleName == LOKI_ERROR_LOG_RULE_NAME) "team-infra-alert" else channelKeyOf(event.source)

    private fun channelKeyOf(source: AlertSource): String =
        when (source) {
            AlertSource.ALERTMANAGER -> "prometheus-alert"
            AlertSource.OCI_COST -> "oci-cost"
            AlertSource.OCI_MONITORING -> "oci-monitoring"
        }

    /**
     * team 문자열 -> 멘션할 Discord role 고정 매핑. 매핑 안 되는 team은 멘션 없이 보낸다.
     *
     * TODO : 각 팀별 roleID 넣어놔야함
     */
    private fun mentionRoleOf(team: String?): DiscordMentionRole? =
        when (team) {
            "infra" -> DiscordMentionRole.INFRA
            else -> null
        }

    /** @return 전송 성공 여부. 예외는 여기서 삼킨다 - 호출자(워처/스케줄러)가 알림 실패로 죽으면 안 된다. */
    fun sendMessage(
        channelId: String,
        content: String,
    ): Boolean =
        try {
            discordRestClient
                .post()
                .uri("/channels/{channelId}/messages", channelId)
                .body(mapOf("content" to content))
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: Exception) {
            log.error("Failed to send Discord message to channel={}", channelId, e)
            false
        }

    private fun formatMessage(event: AlertEvent): String {
        val emoji =
            when (event.status) {
                AlertStatus.FIRING -> "🔥"
                AlertStatus.RESOLVED -> "✅"
                AlertStatus.REPEATED -> "🔁"
            }
        val mentionRole = mentionRoleOf(event.team)
        if (mentionRole == null && event.team != null) {
            log.warn("No Discord mention role mapped for team={}, sending without mention", event.team)
        }

        val meta =
            buildList {
                add("severity: ${event.severity}")
                event.service?.let { add("service: $it") }
                event.resourceName?.let { add("resource: $it") }
            }

        val base =
            buildString {
                mentionRole?.let { append("${it.mention} ") }
                append("$emoji [${event.status}] ${event.title}")
                append("\n" + meta.joinToString(" · "))
                event.description?.let { append("\n$it") }
            }

        // Loki 기반 alert(waffle-world-oci의 ApplicationErrorLog rule)만 로그 컨텍스트를
        // 붙인다. Prometheus metric/OCI alert는 ruleName이 달라 기존 메시지 포맷 그대로
        // 나간다 (하위호환).
        if (event.ruleName != LOKI_ERROR_LOG_RULE_NAME) {
            return base
        }
        return base + lokiContextSuffix(event)
    }

    /** Loki 기반 alert에 로그 원문(대표 몇 줄)과 Grafana Explore 링크를 덧붙인다. */
    private fun lokiContextSuffix(event: AlertEvent): String {
        val namespace = event.service
        val logLines = lokiClient.fetchLogLines(namespace, event.observedAt)
        val exploreUrl = lokiClient.grafanaExploreUrl(namespace, event.observedAt)

        return buildString {
            if (logLines.isNotEmpty()) {
                // Discord 메시지 전체 길이 제한(2000자)을 고려해 원문을 code block으로 감싸되
                // 일부만(앞쪽 몇 줄) 붙인다 - 전체 맥락은 exploreUrl에서 확인.
                val preview = logLines.take(LOG_PREVIEW_LINES).joinToString("\n").take(MAX_LOG_PREVIEW_CHARS)
                append("\n```\n$preview\n```")
            }
            exploreUrl?.let { append("\n🔗 [Grafana에서 전체 로그 보기]($it)") }
        }
    }

    private companion object {
        // waffle-world-oci argocd/loki/resources.yaml의 alert 이름과 반드시 일치해야 한다.
        const val LOKI_ERROR_LOG_RULE_NAME = "ApplicationErrorLog"
        const val LOG_PREVIEW_LINES = 10
        const val MAX_LOG_PREVIEW_CHARS = 1200
    }
}
