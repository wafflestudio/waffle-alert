package com.wafflestudio.alert.domain.evaluator

import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.MetricKind
import com.wafflestudio.alert.domain.model.MetricObservation
import com.wafflestudio.alert.domain.model.MetricProvider
import com.wafflestudio.alert.domain.model.MetricStatistic
import com.wafflestudio.alert.domain.model.MetricUnit
import com.wafflestudio.alert.domain.model.Severity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OciResourceEvaluatorTest {
    private val evaluator = OciResourceEvaluator()
    private val threshold = CpuUtilizationThreshold(warning = 80.0, critical = 90.0)

    @Test
    fun `returns a warning firing event when CPU crosses warning threshold`() {
        val event = evaluator.evaluateCpuUtilization(observation(value = 85.0), threshold)

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
        assertEquals("platform", event.service)
        assertEquals("infra", event.team)
    }

    @Test
    fun `returns a critical firing event when CPU crosses critical threshold`() {
        val event = evaluator.evaluateCpuUtilization(observation(value = 95.0), threshold)

        requireNotNull(event)
        assertEquals(Severity.CRITICAL, event.severity)
        assertEquals("90.0", event.threshold)
    }

    @Test
    fun `does not create an event for a value below warning threshold`() {
        val event = evaluator.evaluateCpuUtilization(observation(value = 75.0), threshold)

        assertNull(event)
    }

    @Test
    fun `uses explicitly configured low thresholds for delivery checks`() {
        val event =
            evaluator.evaluateCpuUtilization(
                observation(value = 1.5),
                CpuUtilizationThreshold(warning = 1.0, critical = 2.0),
            )

        requireNotNull(event)
        assertEquals(Severity.WARNING, event.severity)
        assertEquals("1.0", event.threshold)
    }

    @Test
    fun `ignores observations that are not OCI MySQL CPU utilization`() {
        val event =
            evaluator.evaluateCpuUtilization(
                observation(value = 95.0).copy(metricKind = MetricKind.MEMORY_UTILIZATION),
                threshold,
            )

        assertNull(event)
    }

    private fun observation(value: Double): MetricObservation =
        MetricObservation(
            provider = MetricProvider.OCI,
            resourceType = "mysql",
            resourceId = "ocid1.mysqldbsystem.oc1..example",
            resourceName = "wafflestudio-mysql",
            metricKind = MetricKind.CPU_UTILIZATION,
            metricNamespace = "oci_mysql_database",
            providerMetricName = "CPUUtilization",
            statistic = MetricStatistic.MEAN,
            unit = MetricUnit.PERCENT,
            value = value,
            observedAt = Instant.parse("2026-07-17T00:00:00Z"),
        )
}
