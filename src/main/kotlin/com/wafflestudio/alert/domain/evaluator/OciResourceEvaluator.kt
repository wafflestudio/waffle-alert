package com.wafflestudio.alert.domain.evaluator

import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.MetricKind
import com.wafflestudio.alert.domain.model.MetricObservation
import com.wafflestudio.alert.domain.model.MetricProvider
import com.wafflestudio.alert.domain.model.MetricUnit
import com.wafflestudio.alert.domain.model.Severity

class OciResourceEvaluator {
    fun evaluateCpuUtilization(
        observation: MetricObservation,
        threshold: CpuUtilizationThreshold,
        context: OciAlertContext = OciAlertContext(),
    ): AlertEvent? =
        evaluateUtilization(
            observation = observation,
            metricKind = MetricKind.CPU_UTILIZATION,
            threshold = threshold,
            ruleName = CPU_UTILIZATION_RULE,
            title = "MySQL CPU utilization high",
            metricLabel = "CPU utilization",
            context = context,
        )

    fun evaluateDbVolumeUtilization(
        observation: MetricObservation,
        threshold: DbVolumeUtilizationThreshold,
        context: OciAlertContext = OciAlertContext(),
    ): AlertEvent? =
        evaluateUtilization(
            observation = observation,
            metricKind = MetricKind.VOLUME_UTILIZATION,
            threshold = threshold,
            ruleName = DB_VOLUME_UTILIZATION_RULE,
            title = "MySQL DB volume utilization high",
            metricLabel = "DB volume utilization",
            context = context,
        )

    private fun evaluateUtilization(
        observation: MetricObservation,
        metricKind: MetricKind,
        threshold: UtilizationThreshold,
        ruleName: String,
        title: String,
        metricLabel: String,
        context: OciAlertContext,
    ): AlertEvent? {
        if (!observation.isMysqlUtilization(metricKind)) {
            return null
        }

        val severityAndThreshold =
            when {
                observation.value >= threshold.critical -> Severity.CRITICAL to threshold.critical
                observation.value >= threshold.warning -> Severity.WARNING to threshold.warning
                else -> return null
            }

        val (severity, matchedThreshold) = severityAndThreshold

        return AlertEvent(
            source = AlertSource.OCI_MONITORING,
            status = AlertStatus.FIRING,
            severity = severity,
            fingerprint = "oci-monitoring:mysql:${observation.resourceId}:$ruleName",
            ruleName = ruleName,
            title = title,
            description =
                "${observation.resourceName} $metricLabel is ${observation.value}% " +
                    "(threshold: $matchedThreshold%).",
            service = context.service,
            team = context.team,
            resourceType = observation.resourceType,
            resourceId = observation.resourceId,
            resourceName = observation.resourceName,
            metricName = observation.providerMetricName,
            metricStatistic = observation.statistic.name,
            metricUnit = observation.unit.name,
            value = observation.value.toString(),
            threshold = matchedThreshold.toString(),
            thresholdUnit = observation.unit.name,
            comparisonOperator = COMPARISON_OPERATOR,
            observedAt = observation.observedAt,
            labels = mapOf("provider" to observation.provider.name) + observation.labels,
            rawPayload = observation.rawPayload,
        )
    }

    private fun MetricObservation.isMysqlUtilization(expectedMetricKind: MetricKind): Boolean =
        provider == MetricProvider.OCI &&
            resourceType == MYSQL_RESOURCE_TYPE &&
            metricKind == expectedMetricKind &&
            unit == MetricUnit.PERCENT

    companion object {
        private const val MYSQL_RESOURCE_TYPE = "mysql"
        private const val CPU_UTILIZATION_RULE = "cpu-utilization-high"
        private const val DB_VOLUME_UTILIZATION_RULE = "db-volume-utilization-high"
        private const val COMPARISON_OPERATOR = "GREATER_THAN_OR_EQUAL"
    }
}

data class UtilizationThreshold(
    val warning: Double,
    val critical: Double,
) {
    init {
        require(warning >= 0.0) { "Utilization warning threshold must be non-negative" }
        require(warning < critical) { "Utilization warning threshold must be lower than critical threshold" }
        require(critical <= 100.0) { "Utilization critical threshold must not exceed 100" }
    }
}

typealias CpuUtilizationThreshold = UtilizationThreshold
typealias DbVolumeUtilizationThreshold = UtilizationThreshold

data class OciAlertContext(
    val service: String? = null,
    val team: String? = null,
)
