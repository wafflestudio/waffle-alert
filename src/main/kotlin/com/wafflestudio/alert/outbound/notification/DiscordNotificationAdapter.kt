package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.outbound.notification.routing.DiscordMentionRole
import com.wafflestudio.alert.source.loki.LokiClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class DiscordNotificationAdapter(
    private val discordRestClient: RestClient,
    private val discordProperties: DiscordProperties,
    private val lokiClient: LokiClient,
) : NotificationPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun notify(event: AlertEvent) {
        val channelKey = channelKeyOf(event.source)
        val channelId = discordProperties.channelIds[channelKey]
        if (channelId.isNullOrBlank()) {
            log.warn("Discord channel not configured for source={}, skip notify (fingerprint={})", event.source, event.fingerprint)
            return
        }

        sendMessage(channelId, formatMessage(event))
    }

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

    fun sendMessage(
        channelId: String,
        content: String,
    ) {
        try {
            discordRestClient
                .post()
                .uri("/channels/{channelId}/messages", channelId)
                .body(mapOf("content" to content))
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            log.error("Failed to send Discord message to channel={}", channelId, e)
        }
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

        val base =
            buildString {
                mentionRole?.let { append("${it.mention} ") }
                append("$emoji [${event.status}] ${event.title}")
                append(" (severity: ${event.severity})")
                event.service?.let { append(" [service: $it]") }
                event.resourceName?.let { append(" [resource: $it]") }
                event.description?.let { append("\n$it") }
            }

        // traceId는 Loki 기반 alert(ApplicationErrorLog 등)에만 존재. Prometheus/OCI alert는
        // null이라 기존 메시지 포맷 그대로 나간다 (하위호환).
        if (event.traceId == null) {
            return base
        }
        return base + lokiContextSuffix(event)
    }

    /** Loki 기반 alert에 로그 원문(대표 몇 줄)과 Grafana Explore 링크를 덧붙인다. */
    private fun lokiContextSuffix(event: AlertEvent): String {
        val namespace = event.service
        val logLines = lokiClient.fetchLogLines(namespace, event.traceId, event.observedAt)
        val exploreUrl = lokiClient.grafanaExploreUrl(namespace, event.traceId, event.observedAt)

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
        const val LOG_PREVIEW_LINES = 10
        const val MAX_LOG_PREVIEW_CHARS = 1200
    }
}
