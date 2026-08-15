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
