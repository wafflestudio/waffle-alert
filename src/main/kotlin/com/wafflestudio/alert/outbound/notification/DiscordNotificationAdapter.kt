package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.outbound.notification.routing.DiscordMentionRole
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class DiscordNotificationAdapter(
    private val discordRestClient: RestClient,
    private val discordProperties: DiscordProperties,
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

    /** team 문자열 -> 멘션할 Discord role 고정 매핑. 매핑 안 되는 team은 멘션 없이 보낸다. */
    /**
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

        return buildString {
            mentionRole?.let { append("${it.mention} ") }
            append("$emoji [${event.status}] ${event.title}")
            append(" (severity: ${event.severity})")
            event.service?.let { append(" [service: $it]") }
            event.resourceName?.let { append(" [resource: $it]") }
            event.description?.let { append("\n$it") }
        }
    }
}
