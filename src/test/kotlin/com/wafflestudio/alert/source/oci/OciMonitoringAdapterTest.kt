package com.wafflestudio.alert.source.oci

import com.oracle.bmc.monitoring.MonitoringClient
import com.oracle.bmc.monitoring.model.AggregatedDatapoint
import com.oracle.bmc.monitoring.model.MetricData
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest
import com.oracle.bmc.monitoring.responses.SummarizeMetricsDataResponse
import com.wafflestudio.alert.domain.model.MetricKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

class OciMonitoringAdapterTest {
    private val monitoringClient = mockk<MonitoringClient>()
    private val adapter =
        OciMonitoringAdapter(
            monitoringClient = monitoringClient,
            region = "ap-chuncheon-1",
            clock = Clock.fixed(Instant.parse("2026-07-12T01:05:00Z"), ZoneOffset.UTC),
        )

    @Test
    fun `maps the latest mysql CPU datapoint to an observation`() {
        val requestSlot = slot<SummarizeMetricsDataRequest>()
        every { monitoringClient.summarizeMetricsData(capture(requestSlot)) } returns
            SummarizeMetricsDataResponse
                .builder()
                .items(
                    listOf(
                        metricData(
                            resourceType = "mysql",
                            datapoints =
                                listOf(
                                    datapoint("2026-07-12T01:03:00Z", 81.0),
                                    datapoint("2026-07-12T01:04:00Z", 92.4),
                                ),
                        ),
                        metricData(
                            resourceType = "heatwave",
                            datapoints = listOf(datapoint("2026-07-12T01:04:00Z", 99.0)),
                        ),
                    ),
                )
                .build()

        val observations =
            adapter.fetchMysqlCpuUtilization(
                OciMysqlMetricQuery(
                    compartmentId = "ocid1.compartment.oc1..example",
                    dbSystemId = "ocid1.mysqldbsystem.oc1..example",
                ),
            )

        assertEquals(1, observations.size)
        assertEquals(MetricKind.CPU_UTILIZATION, observations.single().metricKind)
        assertEquals(92.4, observations.single().value)
        assertEquals(Instant.parse("2026-07-12T01:04:00Z"), observations.single().observedAt)
        assertEquals("wafflestudio-mysql", observations.single().resourceName)
        assertEquals("ap-chuncheon-1", observations.single().labels["region"])
        assertEquals("oci_mysql_database", observations.single().labels["namespace"])
        assertEquals(
            "CPUUtilization[1m]{resourceId = \"ocid1.mysqldbsystem.oc1..example\", resourceType = \"mysql\"}.mean()",
            requestSlot.captured.summarizeMetricsDataDetails.query,
        )
    }

    @Test
    fun `maps mysql DB volume utilization without a resource type dimension`() {
        val requestSlot = slot<SummarizeMetricsDataRequest>()
        every { monitoringClient.summarizeMetricsData(capture(requestSlot)) } returns
            SummarizeMetricsDataResponse
                .builder()
                .items(
                    listOf(
                        metricData(
                            metricName = "DbVolumeUtilization",
                            resourceType = null,
                            datapoints = listOf(datapoint("2026-07-12T01:04:00Z", 82.5)),
                        ),
                    ),
                )
                .build()

        val observations =
            adapter.fetchMysqlDbVolumeUtilization(
                OciMysqlMetricQuery(
                    compartmentId = "ocid1.compartment.oc1..example",
                    dbSystemId = "ocid1.mysqldbsystem.oc1..example",
                ),
            )

        assertEquals(1, observations.size)
        assertEquals(MetricKind.VOLUME_UTILIZATION, observations.single().metricKind)
        assertEquals("mysql", observations.single().resourceType)
        assertEquals(82.5, observations.single().value)
        assertEquals(
            "DbVolumeUtilization[1m]{resourceId = \"ocid1.mysqldbsystem.oc1..example\"}.mean()",
            requestSlot.captured.summarizeMetricsDataDetails.query,
        )
    }

    private fun metricData(
        metricName: String = "CPUUtilization",
        resourceType: String?,
        datapoints: List<AggregatedDatapoint>,
    ): MetricData =
        MetricData
            .builder()
            .namespace("oci_mysql_database")
            .compartmentId("ocid1.compartment.oc1..example")
            .name(metricName)
            .dimensions(
                buildMap {
                    put("resourceId", "ocid1.mysqldbsystem.oc1..example")
                    put("resourceName", "wafflestudio-mysql")
                    resourceType?.let { put("resourceType", it) }
                },
            )
            .aggregatedDatapoints(datapoints)
            .build()

    private fun datapoint(
        timestamp: String,
        value: Double,
    ): AggregatedDatapoint =
        AggregatedDatapoint.builder().timestamp(Date.from(Instant.parse(timestamp))).value(value).build()
}
