package com.wafflestudio.alert.domain.model

import java.time.Instant

/**
 * 모든 alert source가 최종적으로 만드는 서비스 공통 사건 모델.
 * 각 source는 이 필드 중 자신이 채울 수 있는 값만 채우고 AlertIngestionService.ingest(event)를 호출한다.
 */
data class AlertEvent(
    val source: AlertSource,
    val status: AlertStatus,
    val severity: Severity,
    val fingerprint: String,
    val ruleName: String,
    val title: String,
    val description: String? = null,
    val service: String? = null,
    val team: String? = null,
    val resourceType: String? = null,
    val resourceId: String? = null,
    val resourceName: String? = null,
    val metricName: String? = null,
    val metricStatistic: String? = null,
    val metricUnit: String? = null,
    val value: String? = null,
    val threshold: String? = null,
    val thresholdUnit: String? = null,
    val comparisonOperator: String? = null,
    val observedAt: Instant = Instant.now(),
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    val rawPayload: String? = null,
)
