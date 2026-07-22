package com.wafflestudio.alert.source.scheduler

import com.wafflestudio.alert.domain.evaluator.ResourceMetricEvaluator
import com.wafflestudio.alert.domain.model.CloudProvider
import com.wafflestudio.alert.domain.model.MetricKind
import com.wafflestudio.alert.domain.model.MetricStatistic
import com.wafflestudio.alert.domain.model.MetricUnit
import com.wafflestudio.alert.domain.model.ResourceMetricObservation
import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.oci.OciMonitoringAdapter
import com.wafflestudio.alert.source.oci.OciMonitoringProperties
import com.wafflestudio.alert.source.oci.OciMysqlDbSystemProperties
import com.wafflestudio.alert.source.oci.OciMysqlMetricQuery
import com.wafflestudio.alert.source.oci.OciMysqlMonitoringProperties
import com.wafflestudio.alert.source.oci.OciMysqlThresholdProperties
import com.wafflestudio.alert.source.oci.OciThresholdProperties
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlin.test.Test

class OciMonitoringSchedulerTest {
    private val adapter = mockk<OciMonitoringAdapter>()
    private val evaluator = ResourceMetricEvaluator()
    private val ingestionService = mockk<AlertIngestionService>()

    @Test
    fun `polls enabled db systems and sends firing events to ingestion`() {
        val enabledDbSystem = dbSystem(enabled = true)
        val disabledDbSystem = dbSystem(id = "disabled", enabled = false)
        val observation = observation(value = 1.5)
        every { adapter.fetchMysqlCpuUtilization(any()) } returns listOf(observation)
        every { adapter.fetchMysqlMemoryUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlCurrentConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlActiveConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlBackupFailure(any()) } returns emptyList()
        every { adapter.fetchMysqlDbVolumeUtilization(any()) } returns emptyList()
        every { ingestionService.ingest(any()) } just Runs

        OciMonitoringScheduler(
            adapter = adapter,
            evaluator = evaluator,
            ingestionService = ingestionService,
            properties =
                properties(
                    mapOf(
                        "enabled" to enabledDbSystem,
                        "disabled" to disabledDbSystem,
                    ),
                ),
        ).poll()

        verify(exactly = 1) {
            adapter.fetchMysqlCpuUtilization(
                OciMysqlMetricQuery(
                    compartmentId = enabledDbSystem.compartmentId,
                    dbSystemId = enabledDbSystem.id,
                    window = properties().queryWindow,
                    resolution = properties().resolution,
                ),
            )
        }
        verify(exactly = 1) { ingestionService.ingest(any()) }
    }

    @Test
    fun `does not send an event when evaluator considers observation normal`() {
        every { adapter.fetchMysqlCpuUtilization(any()) } returns listOf(observation(value = 0.5))
        every { adapter.fetchMysqlMemoryUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlCurrentConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlActiveConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlBackupFailure(any()) } returns emptyList()
        every { adapter.fetchMysqlDbVolumeUtilization(any()) } returns emptyList()

        OciMonitoringScheduler(
            adapter = adapter,
            evaluator = evaluator,
            ingestionService = ingestionService,
            properties = properties(mapOf("enabled" to dbSystem(enabled = true))),
        ).poll()

        verify(exactly = 0) { ingestionService.ingest(any()) }
    }

    @Test
    fun `polls DB volume utilization and sends a firing event to ingestion`() {
        every { adapter.fetchMysqlCpuUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlMemoryUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlCurrentConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlActiveConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlBackupFailure(any()) } returns emptyList()
        every { adapter.fetchMysqlDbVolumeUtilization(any()) } returns
            listOf(
                observation(
                    value = 1.5,
                    metricKind = MetricKind.VOLUME_UTILIZATION,
                    providerMetricName = "DbVolumeUtilization",
                ),
            )
        every { ingestionService.ingest(any()) } just Runs

        OciMonitoringScheduler(
            adapter = adapter,
            evaluator = evaluator,
            ingestionService = ingestionService,
            properties = properties(mapOf("enabled" to dbSystem(enabled = true))),
        ).poll()

        verify(exactly = 1) { adapter.fetchMysqlDbVolumeUtilization(any()) }
        verify(exactly = 1) { ingestionService.ingest(any()) }
    }

    @Test
    fun `polls memory utilization and sends a firing event to ingestion`() {
        every { adapter.fetchMysqlCpuUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlMemoryUtilization(any()) } returns
            listOf(
                observation(
                    value = 1.5,
                    metricKind = MetricKind.MEMORY_UTILIZATION,
                    providerMetricName = "MemoryUtilization",
                ),
            )
        every { adapter.fetchMysqlCurrentConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlActiveConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlBackupFailure(any()) } returns emptyList()
        every { adapter.fetchMysqlDbVolumeUtilization(any()) } returns emptyList()
        every { ingestionService.ingest(any()) } just Runs

        OciMonitoringScheduler(
            adapter = adapter,
            evaluator = evaluator,
            ingestionService = ingestionService,
            properties = properties(mapOf("enabled" to dbSystem(enabled = true))),
        ).poll()

        verify(exactly = 1) { adapter.fetchMysqlMemoryUtilization(any()) }
        verify(exactly = 1) { ingestionService.ingest(any()) }
    }

    @Test
    fun `polls backup failure and sends a firing event to ingestion`() {
        every { adapter.fetchMysqlCpuUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlMemoryUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlCurrentConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlActiveConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlBackupFailure(any()) } returns
            listOf(
                observation(
                    value = 1.0,
                    metricKind = MetricKind.BACKUP_FAILURES,
                    providerMetricName = "BackupFailure",
                    unit = MetricUnit.STATUS,
                ),
            )
        every { adapter.fetchMysqlDbVolumeUtilization(any()) } returns emptyList()
        every { ingestionService.ingest(any()) } just Runs

        OciMonitoringScheduler(
            adapter = adapter,
            evaluator = evaluator,
            ingestionService = ingestionService,
            properties = properties(mapOf("enabled" to dbSystem(enabled = true))),
        ).poll()

        verify(exactly = 1) { adapter.fetchMysqlBackupFailure(any()) }
        verify(exactly = 1) { ingestionService.ingest(any()) }
    }

    @Test
    fun `polls active connections and sends a firing event to ingestion`() {
        every { adapter.fetchMysqlCpuUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlMemoryUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlCurrentConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlActiveConnections(any()) } returns
            listOf(
                observation(
                    value = 85.0,
                    metricKind = MetricKind.ACTIVE_CONNECTIONS,
                    providerMetricName = "ActiveConnections",
                    unit = MetricUnit.COUNT,
                ),
            )
        every { adapter.fetchMysqlBackupFailure(any()) } returns emptyList()
        every { adapter.fetchMysqlDbVolumeUtilization(any()) } returns emptyList()
        every { ingestionService.ingest(any()) } just Runs

        OciMonitoringScheduler(
            adapter = adapter,
            evaluator = evaluator,
            ingestionService = ingestionService,
            properties = properties(mapOf("enabled" to dbSystem(enabled = true))),
        ).poll()

        verify(exactly = 1) { adapter.fetchMysqlActiveConnections(any()) }
        verify(exactly = 1) { ingestionService.ingest(any()) }
    }

    @Test
    fun `polls current connections and sends a firing event to ingestion`() {
        every { adapter.fetchMysqlCpuUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlMemoryUtilization(any()) } returns emptyList()
        every { adapter.fetchMysqlCurrentConnections(any()) } returns
            listOf(
                observation(
                    value = 85.0,
                    metricKind = MetricKind.CURRENT_CONNECTIONS,
                    providerMetricName = "CurrentConnections",
                    unit = MetricUnit.COUNT,
                ),
            )
        every { adapter.fetchMysqlActiveConnections(any()) } returns emptyList()
        every { adapter.fetchMysqlBackupFailure(any()) } returns emptyList()
        every { adapter.fetchMysqlDbVolumeUtilization(any()) } returns emptyList()
        every { ingestionService.ingest(any()) } just Runs

        OciMonitoringScheduler(
            adapter = adapter,
            evaluator = evaluator,
            ingestionService = ingestionService,
            properties = properties(mapOf("enabled" to dbSystem(enabled = true))),
        ).poll()

        verify(exactly = 1) { adapter.fetchMysqlCurrentConnections(any()) }
        verify(exactly = 1) { ingestionService.ingest(any()) }
    }

    private fun properties(
        dbSystems: Map<String, OciMysqlDbSystemProperties> =
            mapOf("default" to dbSystem()),
    ): OciMonitoringProperties =
        OciMonitoringProperties(
            queryWindow = java.time.Duration.ofMinutes(5),
            resolution = "1m",
            mysql = OciMysqlMonitoringProperties(dbSystems),
        )

    private fun dbSystem(
        id: String = "ocid1.mysqldbsystem.oc1..example",
        enabled: Boolean = true,
    ): OciMysqlDbSystemProperties =
        OciMysqlDbSystemProperties(
            id = id,
            compartmentId = "ocid1.compartment.oc1..example",
            enabled = enabled,
            thresholds =
                OciMysqlThresholdProperties(
                    cpuUtilization = OciThresholdProperties(warning = 1.0, critical = 2.0),
                    memoryUtilization = OciThresholdProperties(warning = 1.0, critical = 2.0),
                    currentConnections = OciThresholdProperties(warning = 1.0, critical = 2.0),
                    activeConnections = OciThresholdProperties(warning = 1.0, critical = 2.0),
                    dbVolumeUtilization = OciThresholdProperties(warning = 1.0, critical = 2.0),
                ),
        )

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
