package com.wafflestudio.alert.domain.model

import java.time.Instant

/**
 * 모든 alert source가 최종적으로 만드는 서비스 공통 사건 모델.
 * 각 source는 이 필드 중 자신이 채울 수 있는 값만 채우고 AlertIngestionService.ingest(event)를 호출한다.
 *
 * Discord 메시지는 굵은 [title] -> (설정에서 켠) 메타 줄 -> [description] -> 추가 첨부 순서로 나간다.
 * severity/service/resource는 기본적으로 보이지 않으므로, [title]만 봐도 어느 namespace의 어떤
 * 리소스에서 무슨 일이 일어났는지 알 수 있게 써야 한다 (예: `[Pod Failed] hangsha-prod/hangsha-api-xxx`).
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
