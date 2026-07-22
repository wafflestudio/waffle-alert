package com.wafflestudio.alert.source.oci

import com.oracle.bmc.monitoring.MonitoringClient
import com.oracle.bmc.monitoring.model.MetricData
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest
import com.wafflestudio.alert.domain.model.CloudProvider
import com.wafflestudio.alert.domain.model.MetricKind
import com.wafflestudio.alert.domain.model.MetricStatistic
import com.wafflestudio.alert.domain.model.MetricUnit
import com.wafflestudio.alert.domain.model.ResourceMetricObservation
import java.time.Clock
import java.time.Duration
import java.util.Date

class OciMonitoringAdapter(
    private val monitoringClient: MonitoringClient,
    private val region: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun fetchMysqlCpuUtilization(query: OciMysqlMetricQuery): List<ResourceMetricObservation> =
        fetchMysqlMetric(
            query = query,
            metricName = CPU_UTILIZATION_METRIC,
            metricKind = MetricKind.CPU_UTILIZATION,
            unit = MetricUnit.PERCENT,
            filterByResourceType = true,
        )

    fun fetchMysqlMemoryUtilization(query: OciMysqlMetricQuery): List<ResourceMetricObservation> =
        fetchMysqlMetric(
            query = query,
            metricName = MEMORY_UTILIZATION_METRIC,
            metricKind = MetricKind.MEMORY_UTILIZATION,
            unit = MetricUnit.PERCENT,
            filterByResourceType = true,
        )

    fun fetchMysqlCurrentConnections(query: OciMysqlMetricQuery): List<ResourceMetricObservation> =
        fetchMysqlMetric(
            query = query,
            metricName = CURRENT_CONNECTIONS_METRIC,
            metricKind = MetricKind.CURRENT_CONNECTIONS,
            unit = MetricUnit.COUNT,
            filterByResourceType = false,
        )

    fun fetchMysqlActiveConnections(query: OciMysqlMetricQuery): List<ResourceMetricObservation> =
        fetchMysqlMetric(
            query = query,
            metricName = ACTIVE_CONNECTIONS_METRIC,
            metricKind = MetricKind.ACTIVE_CONNECTIONS,
            unit = MetricUnit.COUNT,
            filterByResourceType = false,
        )

    fun fetchMysqlBackupFailure(query: OciMysqlMetricQuery): List<ResourceMetricObservation> =
        fetchMysqlMetric(
            query = query,
            metricName = BACKUP_FAILURE_METRIC,
            metricKind = MetricKind.BACKUP_FAILURES,
            unit = MetricUnit.STATUS,
            filterByResourceType = false,
        )

    fun fetchMysqlDbVolumeUtilization(query: OciMysqlMetricQuery): List<ResourceMetricObservation> =
        fetchMysqlMetric(
            query = query,
            metricName = DB_VOLUME_UTILIZATION_METRIC,
            metricKind = MetricKind.VOLUME_UTILIZATION,
            unit = MetricUnit.PERCENT,
            filterByResourceType = false,
        )

    private fun fetchMysqlMetric(
        query: OciMysqlMetricQuery,
        metricName: String,
        metricKind: MetricKind,
        unit: MetricUnit,
        filterByResourceType: Boolean,
    ): List<ResourceMetricObservation> {
        val endTime = clock.instant()
        val mql = metricMql(metricName, query.dbSystemId, query.resolution, filterByResourceType)
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
                    ).build(),
            )

        return response.items.orEmpty().mapNotNull { metricData ->
            toMysqlObservation(
                metricData = metricData,
                metricKind = metricKind,
                metricName = metricName,
                unit = unit,
                filterByResourceType = filterByResourceType,
            )
        }
    }

    private fun toMysqlObservation(
        metricData: MetricData,
        metricKind: MetricKind,
        metricName: String,
        unit: MetricUnit,
        filterByResourceType: Boolean,
    ): ResourceMetricObservation? {
        val dimensions = metricData.dimensions.orEmpty()
        val resourceType = dimensions[RESOURCE_TYPE_DIMENSION]
        if (filterByResourceType && resourceType != MYSQL_RESOURCE_TYPE) {
            return null
        }
        if (!filterByResourceType && resourceType != null && resourceType != MYSQL_RESOURCE_TYPE) {
            return null
        }

        val resourceId = dimensions[RESOURCE_ID_DIMENSION] ?: return null
        val observations =
            metricData.aggregatedDatapoints
                .orEmpty()
                .mapNotNull { datapoint ->
                    val timestamp = datapoint.timestamp ?: return@mapNotNull null
                    val value = datapoint.value ?: return@mapNotNull null
                    timestamp.toInstant() to value
                }
        val selectedObservation =
            if (
                metricKind == MetricKind.CPU_UTILIZATION ||
                metricKind == MetricKind.MEMORY_UTILIZATION ||
                metricKind == MetricKind.BACKUP_FAILURES
            ) {
                observations.maxByOrNull { (_, value) -> value }
            } else {
                observations.maxByOrNull { (timestamp) -> timestamp }
            } ?: return null
        val (observedAt, value) = selectedObservation

        return ResourceMetricObservation(
            cloudProvider = CloudProvider.OCI,
            resourceType = resourceType ?: MYSQL_RESOURCE_TYPE,
            resourceId = resourceId,
            resourceName = dimensions[RESOURCE_NAME_DIMENSION] ?: resourceId,
            metricKind = metricKind,
            metricNamespace = metricData.namespace ?: MYSQL_NAMESPACE,
            providerMetricName = metricData.name ?: metricName,
            statistic = MetricStatistic.MEAN,
            unit = unit,
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

    private fun metricMql(
        metricName: String,
        dbSystemId: String,
        resolution: String,
        filterByResourceType: Boolean,
    ): String {
        val resourceTypeFilter =
            if (filterByResourceType) ", $RESOURCE_TYPE_DIMENSION = \"$MYSQL_RESOURCE_TYPE\"" else ""
        val resourceFilter = "$RESOURCE_ID_DIMENSION = \"${escapeMqlValue(dbSystemId)}\"$resourceTypeFilter"
        return "$metricName[$resolution]{$resourceFilter}.mean()"
    }

    private fun escapeMqlValue(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val MYSQL_NAMESPACE = "oci_mysql_database"
        private const val MYSQL_RESOURCE_TYPE = "mysql"
        private const val CPU_UTILIZATION_METRIC = "CPUUtilization"
        private const val MEMORY_UTILIZATION_METRIC = "MemoryUtilization"
        private const val CURRENT_CONNECTIONS_METRIC = "CurrentConnections"
        private const val ACTIVE_CONNECTIONS_METRIC = "ActiveConnections"
        private const val BACKUP_FAILURE_METRIC = "BackupFailure"
        private const val DB_VOLUME_UTILIZATION_METRIC = "DbVolumeUtilization"
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
