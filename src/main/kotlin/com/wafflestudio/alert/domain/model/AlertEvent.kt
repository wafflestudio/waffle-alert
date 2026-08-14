package com.wafflestudio.alert.domain.model

import java.time.Instant

/**
 * source와 무관한 공통 payload. Alertmanager webhook 또는 OCI poller가 만들어내는 공통 입력 모델.
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
    // Loki 기반 alert(ApplicationErrorLog 등) 전용. "none"이면 로그에 traceId가 없다는
    // 뜻으로, Loki 재쿼리 시 namespace+시간창 기반 폴백 조회를 쓴다.
    val traceId: String? = null,
)
