package com.wafflestudio.alert.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class EventLogType {
    FIRING,
    REPEATED,
    RESOLVED,
    NOTIFICATION_SENT,
    NOTIFICATION_FAILED,
}

/** Incident 하나의 타임라인 한 줄. 1 Incident : N EventLog. */
@Entity
@Table(name = "alert_event_logs")
class AlertEventLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "incident_id", nullable = false)
    val incidentId: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    val eventType: EventLogType,
    @Column(length = 1024)
    val message: String? = null,
    @Column(length = 255)
    val value: String? = null,
    @Convert(converter = StringMapJsonConverter::class)
    @Column(columnDefinition = "json")
    val labels: Map<String, String> = emptyMap(),
    @Column(columnDefinition = "TEXT")
    val rawPayload: String? = null,
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        fun of(
            incident: AlertIncident,
            eventType: EventLogType,
            event: AlertEvent? = null,
        ): AlertEventLog =
            AlertEventLog(
                incidentId = incident.id,
                eventType = eventType,
                message = event?.title ?: incident.title,
                value = event?.value,
                labels = event?.labels ?: incident.labels,
                rawPayload = event?.rawPayload,
            )
    }
}
