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

/**
 * 같은 fingerprint로 묶인 하나의 문제 (aggregate root).
 * 상태 전이(touch/resolve/reopen)는 외부에서 status를 직접 바꾸지 않고 이 클래스의 메서드로만 일어난다.
 */
@Entity
@Table(name = "alert_incidents")
class AlertIncident(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val source: AlertSource,
    @Column(nullable = false, unique = true, length = 255)
    val fingerprint: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: AlertStatus,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var severity: Severity,
    @Column(nullable = false, length = 512)
    var title: String,
    @Column(columnDefinition = "TEXT")
    var description: String? = null,
    @Column(length = 128)
    var service: String? = null,
    @Column(length = 255)
    var resource: String? = null,
    @Convert(converter = StringMapJsonConverter::class)
    @Column(columnDefinition = "json")
    var labels: Map<String, String> = emptyMap(),
    @Column(nullable = false)
    val startedAt: Instant,
    @Column(nullable = false)
    var lastSeenAt: Instant,
    @Column
    var resolvedAt: Instant? = null,
    @Column(nullable = false)
    var notifyCount: Int = 0,
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    val isOpen: Boolean
        get() = status != AlertStatus.RESOLVED

    /** 같은 문제가 또 발생함. FIRING -> REPEATED로 전이하고 타임라인 시각만 갱신한다. */
    fun touch(event: AlertEvent) {
        status = AlertStatus.REPEATED
        severity = event.severity
        lastSeenAt = event.observedAt
        updatedAt = Instant.now()
    }

    /** 닫혀 있던 문제가 다시 발생함. */
    fun reopen(event: AlertEvent) {
        status = AlertStatus.FIRING
        severity = event.severity
        lastSeenAt = event.observedAt
        resolvedAt = null
        updatedAt = Instant.now()
    }

    /** 문제 해결됨. */
    fun resolve(event: AlertEvent) {
        status = AlertStatus.RESOLVED
        lastSeenAt = event.observedAt
        resolvedAt = event.observedAt
        updatedAt = Instant.now()
    }

    fun recordNotification() {
        notifyCount += 1
        updatedAt = Instant.now()
    }

    companion object {
        fun openFrom(event: AlertEvent): AlertIncident =
            AlertIncident(
                source = event.source,
                fingerprint = event.fingerprint,
                status = AlertStatus.FIRING,
                severity = event.severity,
                title = event.title,
                description = event.description,
                service = event.service,
                resource = event.resourceName,
                labels = event.labels,
                startedAt = event.observedAt,
                lastSeenAt = event.observedAt,
            )
    }
}
