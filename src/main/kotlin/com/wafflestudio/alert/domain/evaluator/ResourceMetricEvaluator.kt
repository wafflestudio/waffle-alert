package com.wafflestudio.alert.domain.evaluator

import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.MetricKind
import com.wafflestudio.alert.domain.model.MetricObservation
import com.wafflestudio.alert.domain.model.MetricProvider
import com.wafflestudio.alert.domain.model.MetricUnit
import com.wafflestudio.alert.domain.model.Severity

class ResourceMetricEvaluator {
    fun evaluateUtilization(
        observation: MetricObservation,
        threshold: UtilizationThreshold,
        context: AlertContext = AlertContext(),
    ): AlertEvent? {
        val rule = UTILIZATION_RULES.firstOrNull { it.matches(observation) } ?: return null
        val (severity, matchedThreshold) =
            when {
                observation.value >= threshold.critical -> Severity.CRITICAL to threshold.critical
                observation.value >= threshold.warning -> Severity.WARNING to threshold.warning
                else -> return null
            }

        return AlertEvent(
            source = rule.source,
            status = AlertStatus.FIRING,
            severity = severity,
            fingerprint =
                "${rule.source.fingerprintPrefix()}:${observation.resourceType}:" +
                    "${observation.resourceId}:${rule.ruleName}",
            ruleName = rule.ruleName,
            title = rule.title,
            description =
                "${observation.resourceName} ${rule.metricLabel} is ${observation.value}% " +
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

    private fun AlertSource.fingerprintPrefix(): String = name.lowercase().replace('_', '-')

    private data class UtilizationRule(
        val provider: MetricProvider,
        val resourceType: String,
        val metricKind: MetricKind,
        val unit: MetricUnit,
        val source: AlertSource,
        val ruleName: String,
        val title: String,
        val metricLabel: String,
    ) {
        fun matches(observation: MetricObservation): Boolean =
            observation.provider == provider &&
                observation.resourceType == resourceType &&
                observation.metricKind == metricKind &&
                observation.unit == unit
    }

    companion object {
        private const val COMPARISON_OPERATOR = "GREATER_THAN_OR_EQUAL"
        private const val MYSQL_RESOURCE_TYPE = "mysql"

        private val UTILIZATION_RULES =
            listOf(
                UtilizationRule(
                    provider = MetricProvider.OCI,
                    resourceType = MYSQL_RESOURCE_TYPE,
                    metricKind = MetricKind.CPU_UTILIZATION,
                    unit = MetricUnit.PERCENT,
                    source = AlertSource.OCI_MONITORING,
                    ruleName = "cpu-utilization-high",
                    title = "MySQL CPU utilization high",
                    metricLabel = "CPU utilization",
                ),
                UtilizationRule(
                    provider = MetricProvider.OCI,
                    resourceType = MYSQL_RESOURCE_TYPE,
                    metricKind = MetricKind.VOLUME_UTILIZATION,
                    unit = MetricUnit.PERCENT,
                    source = AlertSource.OCI_MONITORING,
                    ruleName = "db-volume-utilization-high",
                    title = "MySQL DB volume utilization high",
                    metricLabel = "DB volume utilization",
                ),
            )
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

data class AlertContext(
    val service: String? = null,
    val team: String? = null,
)
