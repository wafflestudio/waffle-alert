package com.wafflestudio.alert.domain.model

import java.time.Instant

/**
 * A cloud-provider resource metric passed from a polling adapter to an evaluator.
 *
 * This is not an alert and is not used for Alertmanager webhooks. A matching rule converts it to AlertEvent.
 */
data class ResourceMetricObservation(
    val cloudProvider: CloudProvider,
    val resourceType: String,
    val resourceId: String,
    val resourceName: String,
    val metricKind: MetricKind,
    val metricNamespace: String,
    val providerMetricName: String,
    val statistic: MetricStatistic,
    val unit: MetricUnit,
    val value: Double,
    val observedAt: Instant,
    val labels: Map<String, String> = emptyMap(),
    val rawPayload: String? = null,
)

enum class CloudProvider {
    OCI,
    AWS,
}

enum class MetricStatistic {
    MEAN,
    MAX,
    MIN,
    SUM,
    COUNT,
    LAST,
}

enum class MetricUnit {
    PERCENT,
    COUNT,
    BYTES,
    MILLISECONDS,
    STATUS,
    UNKNOWN,
}

enum class MetricKind {
    CPU_UTILIZATION,
    MEMORY_UTILIZATION,
    VOLUME_UTILIZATION,
    CURRENT_CONNECTIONS,
    ACTIVE_CONNECTIONS,
    BACKUP_FAILURES,
}
