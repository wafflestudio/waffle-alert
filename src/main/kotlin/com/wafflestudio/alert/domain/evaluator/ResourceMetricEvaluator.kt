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
    ): AlertEvent? {
        val rule = UTILIZATION_RULES.firstOrNull { it.matches(observation) } ?: return null
        return evaluateMetric(
            observation = observation,
            warningThreshold = threshold.warning,
            criticalThreshold = threshold.critical,
            rule = rule,
        )
    }

    fun evaluateCurrentConnections(
        observation: MetricObservation,
        threshold: CountThreshold,
    ): AlertEvent? =
        evaluateMetric(
            observation = observation,
            warningThreshold = threshold.warning,
            criticalThreshold = threshold.critical,
            rule = CURRENT_CONNECTION_RULE,
        )

    fun evaluateActiveConnections(
        observation: MetricObservation,
        threshold: CountThreshold,
    ): AlertEvent? =
        evaluateMetric(
            observation = observation,
            warningThreshold = threshold.warning,
            criticalThreshold = threshold.critical,
            rule = ACTIVE_CONNECTION_RULE,
        )

    fun evaluateBackupFailure(observation: MetricObservation): AlertEvent? =
        evaluateMetric(
            observation = observation,
            warningThreshold = BACKUP_FAILURE_VALUE,
            criticalThreshold = BACKUP_FAILURE_VALUE,
            rule = BACKUP_FAILURE_RULE,
        )

    private fun evaluateMetric(
        observation: MetricObservation,
        warningThreshold: Double,
        criticalThreshold: Double,
        rule: MetricRule,
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
            service = rule.service,
            team = rule.team,
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
        val service: String,
        val team: String,
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
        private const val OCI_MYSQL_SERVICE = "OCI-DB"
        private const val OCI_MYSQL_TEAM = "infra"
        private const val BACKUP_FAILURE_VALUE = 1.0

        private fun ociMysqlRule(
            metricKind: MetricKind,
            unit: MetricUnit,
            ruleName: String,
            title: String,
            metricLabel: String,
        ) = MetricRule(
            provider = MetricProvider.OCI,
            resourceType = MYSQL_RESOURCE_TYPE,
            metricKind = metricKind,
            unit = unit,
            source = AlertSource.OCI_MONITORING,
            ruleName = ruleName,
            title = title,
            metricLabel = metricLabel,
            service = OCI_MYSQL_SERVICE,
            team = OCI_MYSQL_TEAM,
        )

        private val UTILIZATION_RULES =
            listOf(
                ociMysqlRule(
                    metricKind = MetricKind.CPU_UTILIZATION,
                    unit = MetricUnit.PERCENT,
                    ruleName = "cpu-utilization-high",
                    title = "MySQL CPU utilization high",
                    metricLabel = "CPU utilization",
                ),
                ociMysqlRule(
                    metricKind = MetricKind.MEMORY_UTILIZATION,
                    unit = MetricUnit.PERCENT,
                    ruleName = "memory-utilization-high",
                    title = "MySQL memory utilization high",
                    metricLabel = "Memory utilization",
                ),
                ociMysqlRule(
                    metricKind = MetricKind.VOLUME_UTILIZATION,
                    unit = MetricUnit.PERCENT,
                    ruleName = "db-volume-utilization-high",
                    title = "MySQL DB volume utilization high",
                    metricLabel = "DB volume utilization",
                ),
            )

        private val CURRENT_CONNECTION_RULE =
            ociMysqlRule(
                metricKind = MetricKind.CURRENT_CONNECTIONS,
                unit = MetricUnit.COUNT,
                ruleName = "current-connections-high",
                title = "MySQL current connections high",
                metricLabel = "Current connections",
            )

        private val ACTIVE_CONNECTION_RULE =
            ociMysqlRule(
                metricKind = MetricKind.ACTIVE_CONNECTIONS,
                unit = MetricUnit.COUNT,
                ruleName = "active-connections-high",
                title = "MySQL active connections high",
                metricLabel = "Active connections",
            )

        private val BACKUP_FAILURE_RULE =
            ociMysqlRule(
                metricKind = MetricKind.BACKUP_FAILURES,
                unit = MetricUnit.STATUS,
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
