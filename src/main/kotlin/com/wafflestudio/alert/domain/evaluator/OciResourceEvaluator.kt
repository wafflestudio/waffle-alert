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
    ): AlertEvent? {
        if (!observation.isMysqlCpuUtilization()) {
            return null
        }

        val severityAndThreshold =
            when {
                observation.value >= threshold.critical -> Severity.CRITICAL to threshold.critical
                observation.value >= threshold.warning -> Severity.WARNING to threshold.warning
                else -> return null
            }

        val (severity, matchedThreshold) = severityAndThreshold
        val ruleName = CPU_UTILIZATION_RULE

        return AlertEvent(
            source = AlertSource.OCI_MONITORING,
            status = AlertStatus.FIRING,
            severity = severity,
            fingerprint = "oci-monitoring:mysql:${observation.resourceId}:$ruleName",
            ruleName = ruleName,
            title = "MySQL CPU utilization high",
            description =
                "${observation.resourceName} CPU utilization is ${observation.value}% " +
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

    private fun MetricObservation.isMysqlCpuUtilization(): Boolean =
        provider == MetricProvider.OCI &&
            resourceType == MYSQL_RESOURCE_TYPE &&
            metricKind == MetricKind.CPU_UTILIZATION &&
            unit == MetricUnit.PERCENT

    companion object {
        private const val MYSQL_RESOURCE_TYPE = "mysql"
        private const val CPU_UTILIZATION_RULE = "cpu-utilization-high"
        private const val COMPARISON_OPERATOR = "GREATER_THAN_OR_EQUAL"
    }
}

data class CpuUtilizationThreshold(
    val warning: Double,
    val critical: Double,
) {
    init {
        require(warning >= 0.0) { "CPU warning threshold must be non-negative" }
        require(warning < critical) { "CPU warning threshold must be lower than critical threshold" }
        require(critical <= 100.0) { "CPU critical threshold must not exceed 100" }
    }
}

data class OciAlertContext(
    val service: String? = null,
    val team: String? = null,
)
