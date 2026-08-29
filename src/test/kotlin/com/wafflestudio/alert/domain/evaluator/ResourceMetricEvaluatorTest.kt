package com.wafflestudio.alert.domain.evaluator

import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.CloudProvider
import com.wafflestudio.alert.domain.model.MetricKind
import com.wafflestudio.alert.domain.model.MetricStatistic
import com.wafflestudio.alert.domain.model.MetricUnit
import com.wafflestudio.alert.domain.model.ResourceMetricObservation
import com.wafflestudio.alert.domain.model.Severity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResourceMetricEvaluatorTest {
    private val evaluator = ResourceMetricEvaluator()
    private val threshold = UtilizationThreshold(warning = 80.0, critical = 90.0)

    @Test
    fun `selects OCI MySQL CPU rule and returns a warning event`() {
        val event =
            evaluator.evaluateUtilization(
                observation = observation(value = 85.0),
                threshold = threshold,
            )

        requireNotNull(event)
        assertEquals(AlertSource.OCI_MONITORING, event.source)
        assertEquals(AlertStatus.FIRING, event.status)
        assertEquals(Severity.WARNING, event.severity)
        assertEquals("cpu-utilization-high", event.ruleName)
        assertEquals(
            "oci-monitoring:mysql:ocid1.mysqldbsystem.oc1..example:cpu-utilization-high",
            event.fingerprint,
        )
        assertEquals("85.0", event.value)
        assertEquals("80.0", event.threshold)
        assertEquals("GREATER_THAN_OR_EQUAL", event.comparisonOperator)
        assertEquals("OCI-DB", event.service)
        assertEquals("infra", event.team)
    }

    @Test
    fun `returns a critical event when value crosses critical threshold`() {
        val event = evaluator.evaluateUtilization(observation(value = 95.0), threshold)

        requireNotNull(event)
        assertEquals(Severity.CRITICAL, event.severity)
        assertEquals("90.0", event.threshold)
    }

    @Test
    fun `selects OCI MySQL DB volume utilization rule`() {
        val event =
            evaluator.evaluateUtilization(
                observation(
                    value = 85.0,
                    metricKind = MetricKind.VOLUME_UTILIZATION,
                    providerMetricName = "DbVolumeUtilization",
                ),
                threshold,
            )

        requireNotNull(event)
        assertEquals("db-volume-utilization-high", event.ruleName)
        assertEquals(
            "oci-monitoring:mysql:ocid1.mysqldbsystem.oc1..example:db-volume-utilization-high",
            event.fingerprint,
        )
        assertEquals("DbVolumeUtilization", event.metricName)
    }

    @Test
    fun `selects OCI MySQL memory utilization rule`() {
        val event =
            evaluator.evaluateUtilization(
                observation(
                    value = 85.0,
                    metricKind = MetricKind.MEMORY_UTILIZATION,
                    providerMetricName = "MemoryUtilization",
                ),
                threshold,
            )

        requireNotNull(event)
        assertEquals("memory-utilization-high", event.ruleName)
        assertEquals("MemoryUtilization", event.metricName)
    }

    @Test
    fun `selects OCI MySQL current connections rule without percent formatting`() {
        val event =
            evaluator.evaluateCurrentConnections(
                observation(
                    value = 85.0,
                    metricKind = MetricKind.CURRENT_CONNECTIONS,
                    providerMetricName = "CurrentConnections",
                    unit = MetricUnit.COUNT,
                ),
                CountThreshold(warning = 80.0, critical = 100.0),
            )

        requireNotNull(event)
        assertEquals("current-connections-high", event.ruleName)
        assertEquals(
            "oci-monitoring:mysql:ocid1.mysqldbsystem.oc1..example:current-connections-high",
            event.fingerprint,
        )
        assertEquals("85.0", event.value)
        assertEquals("80.0", event.threshold)
        assertEquals(
            "wafflestudio-mysql Current connections is 85.0 (threshold: 80.0).",
            event.description,
        )
    }

    @Test
    fun `selects OCI MySQL active connections rule`() {
        val event =
            evaluator.evaluateActiveConnections(
                observation(
                    value = 85.0,
                    metricKind = MetricKind.ACTIVE_CONNECTIONS,
                    providerMetricName = "ActiveConnections",
                    unit = MetricUnit.COUNT,
                ),
                CountThreshold(warning = 80.0, critical = 100.0),
            )

        requireNotNull(event)
        assertEquals("active-connections-high", event.ruleName)
        assertEquals(
            "oci-monitoring:mysql:ocid1.mysqldbsystem.oc1..example:active-connections-high",
            event.fingerprint,
        )
        assertEquals("ActiveConnections", event.metricName)
        assertEquals("wafflestudio-mysql Active connections is 85.0 (threshold: 80.0).", event.description)
    }

    @Test
    fun `selects OCI MySQL backup failure rule as critical`() {
        val event =
            evaluator.evaluateBackupFailure(
                observation(
                    value = 1.0,
                    metricKind = MetricKind.BACKUP_FAILURES,
                    providerMetricName = "BackupFailure",
                    unit = MetricUnit.STATUS,
                ),
            )

        requireNotNull(event)
        assertEquals(Severity.CRITICAL, event.severity)
        assertEquals("backup-failure", event.ruleName)
        assertEquals(
            "oci-monitoring:mysql:ocid1.mysqldbsystem.oc1..example:backup-failure",
            event.fingerprint,
        )
        assertEquals("1.0", event.threshold)
        assertEquals("STATUS", event.thresholdUnit)
        assertEquals("wafflestudio-mysql Backup failure is FAILED (threshold: FAILED).", event.description)
    }

    @Test
    fun `does not create an event for a successful backup status`() {
        assertNull(
            evaluator.evaluateBackupFailure(
                observation(
                    value = 0.0,
                    metricKind = MetricKind.BACKUP_FAILURES,
                    providerMetricName = "BackupFailure",
                    unit = MetricUnit.STATUS,
                ),
            ),
        )
    }

    @Test
    fun `returns no event below warning threshold`() {
        assertNull(evaluator.evaluateUtilization(observation(value = 75.0), threshold))
    }

    @Test
    fun `supports explicitly configured low thresholds`() {
        val event =
            evaluator.evaluateUtilization(
                observation(value = 1.5),
                UtilizationThreshold(warning = 1.0, critical = 2.0),
            )

        requireNotNull(event)
        assertEquals(Severity.WARNING, event.severity)
        assertEquals("1.0", event.threshold)
    }

    @Test
    fun `returns no event when cloud provider has no matching rule`() {
        val awsObservation = observation(value = 95.0).copy(cloudProvider = CloudProvider.AWS)

        assertNull(evaluator.evaluateUtilization(awsObservation, threshold))
    }

    @Test
    fun `returns no event when metric kind has no matching rule`() {
        val unsupportedObservation = observation(value = 95.0).copy(metricKind = MetricKind.BACKUP_FAILURES)

        assertNull(evaluator.evaluateUtilization(unsupportedObservation, threshold))
    }

    @Test
    fun `does not apply a count rule to a utilization observation`() {
        assertNull(
            evaluator.evaluateCurrentConnections(
                observation(value = 95.0),
                CountThreshold(warning = 80.0, critical = 100.0),
            ),
        )
    }

    private fun observation(
        value: Double,
        metricKind: MetricKind = MetricKind.CPU_UTILIZATION,
        providerMetricName: String = "CPUUtilization",
        unit: MetricUnit = MetricUnit.PERCENT,
    ): ResourceMetricObservation =
        ResourceMetricObservation(
            cloudProvider = CloudProvider.OCI,
            resourceType = "mysql",
            resourceId = "ocid1.mysqldbsystem.oc1..example",
            resourceName = "wafflestudio-mysql",
            metricKind = metricKind,
            metricNamespace = "oci_mysql_database",
            providerMetricName = providerMetricName,
            statistic = MetricStatistic.MEAN,
            unit = unit,
            value = value,
            observedAt = Instant.parse("2026-07-17T00:00:00Z"),
        )
}
