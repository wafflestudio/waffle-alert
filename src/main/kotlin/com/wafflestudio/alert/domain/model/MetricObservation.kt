package com.wafflestudio.alert.domain.model

import java.time.Instant

/**
 * A provider-neutral metric observation passed from a source adapter to an evaluator.
 *
 * This is not an alert. It only describes one selected observed value for one resource.
 */
data class MetricObservation(
    val provider: MetricProvider,
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

enum class MetricProvider {
    OCI,
    AWS_CLOUDWATCH,
    PROMETHEUS,
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
