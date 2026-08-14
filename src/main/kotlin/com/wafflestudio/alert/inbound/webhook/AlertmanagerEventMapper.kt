package com.wafflestudio.alert.inbound.webhook

import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.Severity
import com.wafflestudio.alert.inbound.webhook.dto.AlertmanagerAlert
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime

/** Alertmanager alert 1건 → 공통 AlertEvent. team 변환/멘션 판단은 이번 범위 밖(D/E). */
@Component
class AlertmanagerEventMapper {
    fun toAlertEvent(alert: AlertmanagerAlert): AlertEvent {
        val labels = alert.labels
        return AlertEvent(
            source = AlertSource.ALERTMANAGER,
            status = statusOf(alert.status),
            severity = severityOf(labels["severity"]),
            fingerprint = alert.fingerprint,
            ruleName = labels["alertname"] ?: "unknown",
            title = alert.annotations["summary"] ?: labels["alertname"] ?: "alert",
            description = alert.annotations["description"],
            service = labels["namespace"],
            team = null,
            resourceName =
                labels["pod"]
                    ?: labels["persistentvolumeclaim"]
                    ?: labels["node"]
                    ?: labels["instance"],
            observedAt = parseInstant(alert.startsAt),
            labels = labels,
            annotations = alert.annotations,
            // Loki 기반 alert(ApplicationErrorLog)만 이 annotation을 채운다. Prometheus metric
            // alert에는 없으므로 null. "none"은 Loki alert rule이 로그에서 trace_id를 못 뽑았을
            // 때 명시적으로 채우는 값 — 그대로 전달해 다운스트림(Loki 재쿼리)이 폴백을 판단한다.
            traceId = alert.annotations["trace_id"],
        )
    }

    private fun statusOf(raw: String): AlertStatus = if (raw.lowercase() == "resolved") AlertStatus.RESOLVED else AlertStatus.FIRING

    private fun severityOf(raw: String?): Severity =
        when (raw?.lowercase()) {
            "critical" -> Severity.CRITICAL
            "warning" -> Severity.WARNING
            else -> Severity.INFO
        }

    private fun parseInstant(raw: String?): Instant =
        raw?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: Instant.now()
}
