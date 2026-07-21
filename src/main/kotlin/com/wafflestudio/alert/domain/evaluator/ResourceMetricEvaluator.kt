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
        return evaluateMetric(
            observation = observation,
            warningThreshold = threshold.warning,
            criticalThreshold = threshold.critical,
            rule = rule,
            context = context,
        )
    }

    fun evaluateCurrentConnections(
        observation: MetricObservation,
        threshold: CountThreshold,
        context: AlertContext = AlertContext(),
    ): AlertEvent? =
        evaluateMetric(
            observation = observation,
            warningThreshold = threshold.warning,
            criticalThreshold = threshold.critical,
            rule = CURRENT_CONNECTION_RULE,
            context = context,
        )

    fun evaluateActiveConnections(
        observation: MetricObservation,
        threshold: CountThreshold,
        context: AlertContext = AlertContext(),
    ): AlertEvent? =
        evaluateMetric(
            observation = observation,
            warningThreshold = threshold.warning,
            criticalThreshold = threshold.critical,
            rule = ACTIVE_CONNECTION_RULE,
            context = context,
        )

    fun evaluateBackupFailure(
        observation: MetricObservation,
        context: AlertContext = AlertContext(),
    ): AlertEvent? =
        evaluateMetric(
            observation = observation,
            warningThreshold = BACKUP_FAILURE_VALUE,
            criticalThreshold = BACKUP_FAILURE_VALUE,
            rule = BACKUP_FAILURE_RULE,
            context = context,
        )

    private fun evaluateMetric(
        observation: MetricObservation,
        warningThreshold: Double,
        criticalThreshold: Double,
        rule: MetricRule,
        context: AlertContext,
    ): AlertEvent? {
        if (!rule.matches(observation)) return null
        val (severity, matchedThreshold) =
            when {
                observation.value >= criticalThreshold -> Severity.CRITICAL to criticalThreshold
                observation.value >= warningThreshold -> Severity.WARNING to warningThreshold
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
                "${observation.resourceName} ${rule.metricLabel} is " +
                    "${formatValue(observation.value, observation.unit)} " +
                    "(threshold: ${formatValue(matchedThreshold, observation.unit)}).",
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

    private fun formatValue(
        value: Double,
        unit: MetricUnit,
    ): String =
        when (unit) {
            MetricUnit.PERCENT -> "$value%"
            MetricUnit.STATUS -> if (value >= BACKUP_FAILURE_VALUE) "FAILED" else "OK"
            else -> value.toString()
        }

    private fun AlertSource.fingerprintPrefix(): String = name.lowercase().replace('_', '-')

    private data class MetricRule(
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
        private const val BACKUP_FAILURE_VALUE = 1.0

        private val UTILIZATION_RULES =
            listOf(
                MetricRule(
                    provider = MetricProvider.OCI,
                    resourceType = MYSQL_RESOURCE_TYPE,
                    metricKind = MetricKind.CPU_UTILIZATION,
                    unit = MetricUnit.PERCENT,
                    source = AlertSource.OCI_MONITORING,
                    ruleName = "cpu-utilization-high",
                    title = "MySQL CPU utilization high",
                    metricLabel = "CPU utilization",
                ),
                MetricRule(
                    provider = MetricProvider.OCI,
                    resourceType = MYSQL_RESOURCE_TYPE,
                    metricKind = MetricKind.MEMORY_UTILIZATION,
                    unit = MetricUnit.PERCENT,
                    source = AlertSource.OCI_MONITORING,
                    ruleName = "memory-utilization-high",
                    title = "MySQL memory utilization high",
                    metricLabel = "Memory utilization",
                ),
                MetricRule(
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

        private val CURRENT_CONNECTION_RULE =
            MetricRule(
                provider = MetricProvider.OCI,
                resourceType = MYSQL_RESOURCE_TYPE,
                metricKind = MetricKind.CURRENT_CONNECTIONS,
                unit = MetricUnit.COUNT,
                source = AlertSource.OCI_MONITORING,
                ruleName = "current-connections-high",
                title = "MySQL current connections high",
                metricLabel = "Current connections",
            )

        private val ACTIVE_CONNECTION_RULE =
            MetricRule(
                provider = MetricProvider.OCI,
                resourceType = MYSQL_RESOURCE_TYPE,
                metricKind = MetricKind.ACTIVE_CONNECTIONS,
                unit = MetricUnit.COUNT,
                source = AlertSource.OCI_MONITORING,
                ruleName = "active-connections-high",
                title = "MySQL active connections high",
                metricLabel = "Active connections",
            )

        private val BACKUP_FAILURE_RULE =
            MetricRule(
                provider = MetricProvider.OCI,
                resourceType = MYSQL_RESOURCE_TYPE,
                metricKind = MetricKind.BACKUP_FAILURES,
                unit = MetricUnit.STATUS,
                source = AlertSource.OCI_MONITORING,
                ruleName = "backup-failure",
                title = "MySQL backup failure",
                metricLabel = "Backup failure",
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

data class CountThreshold(
    val warning: Double,
    val critical: Double,
) {
    init {
        require(warning >= 0.0) { "Count warning threshold must be non-negative" }
        require(warning < critical) { "Count warning threshold must be lower than critical threshold" }
    }
}

data class AlertContext(
    val service: String? = null,
    val team: String? = null,
)
