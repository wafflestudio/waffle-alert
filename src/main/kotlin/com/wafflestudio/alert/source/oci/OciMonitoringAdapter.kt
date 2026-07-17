package com.wafflestudio.alert.source.oci

import com.oracle.bmc.monitoring.MonitoringClient
import com.oracle.bmc.monitoring.model.MetricData
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest
import com.wafflestudio.alert.domain.model.MetricKind
import com.wafflestudio.alert.domain.model.MetricObservation
import com.wafflestudio.alert.domain.model.MetricProvider
import com.wafflestudio.alert.domain.model.MetricStatistic
import com.wafflestudio.alert.domain.model.MetricUnit
import java.time.Clock
import java.time.Duration
import java.util.Date

class OciMonitoringAdapter(
    private val monitoringClient: MonitoringClient,
    private val region: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun fetchMysqlCpuUtilization(query: OciMysqlMetricQuery): List<MetricObservation> {
        val endTime = clock.instant()
        val mql = cpuUtilizationMql(query.dbSystemId, query.resolution)
        val response =
            monitoringClient.summarizeMetricsData(
                SummarizeMetricsDataRequest
                    .builder()
                    .compartmentId(query.compartmentId)
                    .summarizeMetricsDataDetails(
                        SummarizeMetricsDataDetails
                            .builder()
                            .namespace(MYSQL_NAMESPACE)
                            .query(mql)
                            .startTime(Date.from(endTime.minus(query.window)))
                            .endTime(Date.from(endTime))
                            .resolution(query.resolution)
                            .build(),
                    )
                    .build(),
            )

        return response.items.orEmpty().mapNotNull(::toMysqlCpuObservation)
    }

    private fun toMysqlCpuObservation(metricData: MetricData): MetricObservation? {
        val dimensions = metricData.dimensions.orEmpty()
        if (dimensions[RESOURCE_TYPE_DIMENSION] != MYSQL_RESOURCE_TYPE) {
            return null
        }

        val resourceId = dimensions[RESOURCE_ID_DIMENSION] ?: return null
        val latestObservation =
            metricData.aggregatedDatapoints
                .orEmpty()
                .mapNotNull { datapoint ->
                    val timestamp = datapoint.timestamp ?: return@mapNotNull null
                    val value = datapoint.value ?: return@mapNotNull null
                    timestamp.toInstant() to value
                }.maxByOrNull { (timestamp) -> timestamp }
                ?: return null
        val (observedAt, value) = latestObservation

        return MetricObservation(
            provider = MetricProvider.OCI,
            resourceType = MYSQL_RESOURCE_TYPE,
            resourceId = resourceId,
            resourceName = dimensions[RESOURCE_NAME_DIMENSION] ?: resourceId,
            metricKind = MetricKind.CPU_UTILIZATION,
            metricNamespace = metricData.namespace ?: MYSQL_NAMESPACE,
            providerMetricName = metricData.name ?: CPU_UTILIZATION_METRIC,
            statistic = MetricStatistic.MEAN,
            unit = MetricUnit.PERCENT,
            value = value,
            observedAt = observedAt,
            labels =
                buildMap {
                    putAll(dimensions)
                    put("namespace", metricData.namespace ?: MYSQL_NAMESPACE)
                    metricData.compartmentId?.let { put("compartmentId", it) }
                    put("region", region)
                },
        )
    }

    private fun cpuUtilizationMql(
        dbSystemId: String,
        resolution: String,
    ): String =
        "$CPU_UTILIZATION_METRIC[$resolution]{$RESOURCE_ID_DIMENSION = \"${escapeMqlValue(dbSystemId)}\", " +
            "$RESOURCE_TYPE_DIMENSION = \"$MYSQL_RESOURCE_TYPE\"}.mean()"

    private fun escapeMqlValue(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val MYSQL_NAMESPACE = "oci_mysql_database"
        private const val MYSQL_RESOURCE_TYPE = "mysql"
        private const val CPU_UTILIZATION_METRIC = "CPUUtilization"
        private const val RESOURCE_ID_DIMENSION = "resourceId"
        private const val RESOURCE_NAME_DIMENSION = "resourceName"
        private const val RESOURCE_TYPE_DIMENSION = "resourceType"
    }
}

data class OciMysqlMetricQuery(
    val compartmentId: String,
    val dbSystemId: String,
    val window: Duration = Duration.ofMinutes(5),
    val resolution: String = "1m",
)
