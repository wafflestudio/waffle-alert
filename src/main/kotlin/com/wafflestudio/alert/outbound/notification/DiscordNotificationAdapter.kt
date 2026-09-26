package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.config.DiscordProperties
import com.wafflestudio.alert.config.MessageFormatProperties
import com.wafflestudio.alert.config.MetaField
import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.outbound.notification.routing.ChannelType
import com.wafflestudio.alert.outbound.notification.routing.RoutingPolicy
import com.wafflestudio.alert.source.loki.LokiClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class DiscordNotificationAdapter(
    private val discordRestClient: RestClient,
    private val discordProperties: DiscordProperties,
    private val lokiClient: LokiClient,
    private val routingPolicy: RoutingPolicy,
    private val messageFormatProperties: MessageFormatProperties,
) : NotificationPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun notify(event: AlertEvent): Boolean {
        // 해소 알림은 보내지 않는다. Alertmanager의 send_resolved도 꺼져 있지만
        // (waffle-world-oci argocd/prometheus/values.yaml) 설정이 되돌아가도 여기서 막는다.
        if (event.status == AlertStatus.RESOLVED) {
            log.debug("Skip RESOLVED (fingerprint={})", event.fingerprint)
            return true
        }

        // namespace가 alert.team-mapping.namespaces에 매핑돼 있으면 alert 성격(app/infra)에 맞는
        // 팀 채널로 우선 보내고, 매핑이 없으면 기본 채널로 폴백한다.
        val channelType = channelTypeOf(event)
        val channelKey = routingPolicy.channelKeyForNamespace(event.service, channelType) ?: defaultChannelKeyFor(event)
        val channelId = discordProperties.channelIds[channelKey]
        if (channelId.isNullOrBlank()) {
            log.warn("Discord channel not configured for channelKey={}, skip notify (fingerprint={})", channelKey, event.fingerprint)
            return false
        }

        return sendMessage(channelId, formatMessage(event))
    }

    /** Loki 기반 애플리케이션 에러 로그만 APPLICATION, 그 외(Prometheus/K8s/OCI)는 WORKLOAD. */
    private fun channelTypeOf(event: AlertEvent): ChannelType =
        if (event.isApplicationErrorLog) ChannelType.APPLICATION else ChannelType.WORKLOAD

    /**
     * Loki 기반 alert(ApplicationErrorLog)는 namespace 매핑이 없으면 team-infra-alert로
     * 보낸다 - Loki 파이프라인 자체가 infra팀 소유이고, ApplicationErrorLog 룰이 team 매핑이
     * 안 된 시스템/미할당 네임스페이스(loki, argocd, external-secrets 등)에도 넓게 발동하는데
     * 이걸 레거시 Prometheus metric alert용 채널(prometheus-alert)에 섞으면 안 된다.
     * 그 외 alert(Prometheus metric, OCI 등)는 기존처럼 source 기준으로 보낸다.
     */
    private fun defaultChannelKeyFor(event: AlertEvent): String =
        if (event.isApplicationErrorLog) "team-infra-alert" else channelKeyOf(event.source)

    private fun channelKeyOf(source: AlertSource): String =
        when (source) {
            AlertSource.ALERTMANAGER -> "prometheus-alert"
            AlertSource.OCI_COST -> "oci-cost"
            AlertSource.OCI_MONITORING -> "oci-monitoring"
            AlertSource.K8S -> "k8s-alert"
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

    /**
     * 메시지는 title -> 메타 줄(설정에서 켠 필드만) -> description -> 추가 첨부 순서다.
     * namespace와 리소스 이름은 title에 들어 있어야 한다 (AlertEvent.title 참고).
     */
    private fun formatMessage(event: AlertEvent): String {
        val base =
            buildString {
                append(event.title.escapeMarkdown())
                metaLine(event)?.let { append("\n$it") }
                event.description?.takeUnless { it.isBlank() }?.let { append("\n$it") }
                event.queryWindowLine()?.let { append("\n$it") }
            }

        // Loki 기반 alert(waffle-world-oci의 ApplicationErrorLog rule)만 로그 원문과
        // Grafana 링크를 붙인다.
        if (!event.isApplicationErrorLog) {
            return base
        }
        return base + lokiContextSuffix(event)
    }

    /**
     * alert.message.meta-fields에서 켠 필드만 `severity: X · service: Y` 형태로 이어 붙인다.
     * 켠 필드가 없거나 값이 모두 비어 있으면 null - 메타 줄 자체를 넣지 않는다.
     */
    private fun metaLine(event: AlertEvent): String? =
        messageFormatProperties
            .fieldsFor(event.source)
            .distinct()
            .sorted()
            .mapNotNull { field ->
                when (field) {
                    MetaField.SEVERITY -> "severity: ${event.severity}"
                    MetaField.SERVICE -> event.service?.let { "service: $it" }
                    MetaField.RESOURCE -> event.resourceName?.let { "resource: $it" }
                }
            }.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")

    /**
     * title의 Discord 마크다운 문자를 이스케이프한다. Prometheus/Loki summary에는 라벨 값이 그대로
     * 들어오므로, 거기에 `*`나 `_`가 섞여도 기울임·굵게 같은 서식이 적용되지 않게 한다.
     */
    private fun String.escapeMarkdown(): String = replace(MARKDOWN_SPECIAL_CHARS) { "\\${it.value}" }

    private fun AlertEvent.queryWindowLine(): String? {
        if (source != AlertSource.OCI_MONITORING) return null
        val start = labels[QUERY_START_TIME_LABEL]?.toInstantOrNull() ?: return null
        val end = labels[QUERY_END_TIME_LABEL]?.toInstantOrNull() ?: return null
        return "조회 범위: ${KST_FORMAT.format(start)} ~ ${KST_FORMAT.format(end)} KST"
    }

    private fun String.toInstantOrNull(): Instant? = runCatching(Instant::parse).getOrNull()

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
            exploreUrl?.let { append("\n🔗 [View full logs in Grafana]($it)") }
        }
    }

    /** waffle-world-oci argocd/loki/resources.yaml의 ApplicationErrorLog rule이 만든 alert인지. */
    private val AlertEvent.isApplicationErrorLog: Boolean
        get() = ruleName == LOKI_ERROR_LOG_RULE_NAME

    private companion object {
        // waffle-world-oci argocd/loki/resources.yaml의 alert 이름과 반드시 일치해야 한다.
        const val LOKI_ERROR_LOG_RULE_NAME = "ApplicationErrorLog"
        const val LOG_PREVIEW_LINES = 10
        const val MAX_LOG_PREVIEW_CHARS = 1200
        private const val QUERY_START_TIME_LABEL = "queryStartTime"
        private const val QUERY_END_TIME_LABEL = "queryEndTime"
        private val MARKDOWN_SPECIAL_CHARS = Regex("""[\\*_~`|]""")
        private val KST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))
    }
}
