package com.wafflestudio.alert.source.scheduler

import com.wafflestudio.alert.domain.evaluator.AlertContext
import com.wafflestudio.alert.domain.evaluator.CountThreshold
import com.wafflestudio.alert.domain.evaluator.ResourceMetricEvaluator
import com.wafflestudio.alert.domain.evaluator.UtilizationThreshold
import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.MetricObservation
import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.oci.OciMonitoringAdapter
import com.wafflestudio.alert.source.oci.OciMonitoringProperties
import com.wafflestudio.alert.source.oci.OciMysqlDbSystemProperties
import com.wafflestudio.alert.source.oci.OciMysqlMetricQuery
import com.wafflestudio.alert.source.oci.OciThresholdProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

class OciMonitoringScheduler(
    private val adapter: OciMonitoringAdapter,
    private val evaluator: ResourceMetricEvaluator,
    private val ingestionService: AlertIngestionService,
    private val properties: OciMonitoringProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${alert.oci-monitoring.polling-interval-ms:60000}")
    fun poll() {
        properties.mysql.dbSystems
            .values
            .filter { it.enabled }
            .forEach(::pollDbSystem)
    }

    private fun pollDbSystem(dbSystem: OciMysqlDbSystemProperties) {
        val query =
            OciMysqlMetricQuery(
                compartmentId = dbSystem.compartmentId,
                dbSystemId = dbSystem.id,
                window = properties.queryWindow,
                resolution = properties.resolution,
            )
        val context = AlertContext(dbSystem.service, dbSystem.team)

        pollUtilization(
            dbSystemId = dbSystem.id,
            metricName = "CPUUtilization",
            threshold = dbSystem.thresholds.cpuUtilization,
            fetch = { adapter.fetchMysqlCpuUtilization(query) },
            context = context,
        )
        pollUtilization(
            dbSystemId = dbSystem.id,
            metricName = "MemoryUtilization",
            threshold = dbSystem.thresholds.memoryUtilization,
            fetch = { adapter.fetchMysqlMemoryUtilization(query) },
            context = context,
        )
        pollMetric(
            dbSystemId = dbSystem.id,
            metricName = "CurrentConnections",
            fetch = { adapter.fetchMysqlCurrentConnections(query) },
            evaluate = { observation ->
                val threshold = dbSystem.thresholds.currentConnections
                evaluator.evaluateCurrentConnections(
                    observation = observation,
                    threshold = CountThreshold(threshold.warning, threshold.critical),
                    context = context,
                )
            },
        )
        pollMetric(
            dbSystemId = dbSystem.id,
            metricName = "ActiveConnections",
            fetch = { adapter.fetchMysqlActiveConnections(query) },
            evaluate = { observation ->
                val threshold = dbSystem.thresholds.activeConnections
                evaluator.evaluateActiveConnections(
                    observation = observation,
                    threshold = CountThreshold(threshold.warning, threshold.critical),
                    context = context,
                )
            },
        )
        pollMetric(
            dbSystemId = dbSystem.id,
            metricName = "BackupFailure",
            fetch = { adapter.fetchMysqlBackupFailure(query) },
            evaluate = { observation ->
                evaluator.evaluateBackupFailure(
                    observation = observation,
                    context = context,
                )
            },
        )
        pollUtilization(
            dbSystemId = dbSystem.id,
            metricName = "DbVolumeUtilization",
            threshold = dbSystem.thresholds.dbVolumeUtilization,
            fetch = { adapter.fetchMysqlDbVolumeUtilization(query) },
            context = context,
        )
    }

    private fun pollUtilization(
        dbSystemId: String,
        metricName: String,
        threshold: OciThresholdProperties,
        fetch: () -> List<MetricObservation>,
        context: AlertContext,
    ) {
        pollMetric(
            dbSystemId = dbSystemId,
            metricName = metricName,
            fetch = fetch,
            evaluate = { observation ->
                evaluator.evaluateUtilization(
                    observation = observation,
                    threshold = UtilizationThreshold(threshold.warning, threshold.critical),
                    context = context,
                )
            },
        )
    }

    private fun pollMetric(
        dbSystemId: String,
        metricName: String,
        fetch: () -> List<MetricObservation>,
        evaluate: (MetricObservation) -> AlertEvent?,
    ) {
        try {
            fetch().forEach { observation ->
                evaluate(observation)?.let(ingestionService::ingest)
            }
        } catch (exception: Exception) {
            log.error(
                "Failed to poll OCI MySQL metric={} dbSystemId={}",
                metricName,
                dbSystemId,
                exception,
            )
        }
    }
}
