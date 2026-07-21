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

        pollMetric(
            dbSystemId = dbSystem.id,
            metricName = "CPUUtilization",
            fetch = { adapter.fetchMysqlCpuUtilization(query) },
            evaluate = { observation ->
                val threshold = dbSystem.thresholds.cpuUtilization
                evaluator.evaluateUtilization(
                    observation = observation,
                    threshold = UtilizationThreshold(threshold.warning, threshold.critical),
                    context = context,
                )
            },
        )
        pollMetric(
            dbSystemId = dbSystem.id,
            metricName = "MemoryUtilization",
            fetch = { adapter.fetchMysqlMemoryUtilization(query) },
            evaluate = { observation ->
                val threshold = dbSystem.thresholds.memoryUtilization
                evaluator.evaluateUtilization(
                    observation = observation,
                    threshold = UtilizationThreshold(threshold.warning, threshold.critical),
                    context = context,
                )
            },
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
        pollMetric(
            dbSystemId = dbSystem.id,
            metricName = "DbVolumeUtilization",
            fetch = { adapter.fetchMysqlDbVolumeUtilization(query) },
            evaluate = { observation ->
                val threshold = dbSystem.thresholds.dbVolumeUtilization
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
