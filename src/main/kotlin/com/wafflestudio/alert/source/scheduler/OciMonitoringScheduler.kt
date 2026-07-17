package com.wafflestudio.alert.source.scheduler

import com.wafflestudio.alert.domain.evaluator.CpuUtilizationThreshold
import com.wafflestudio.alert.domain.evaluator.OciAlertContext
import com.wafflestudio.alert.domain.evaluator.OciResourceEvaluator
import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.oci.OciMonitoringAdapter
import com.wafflestudio.alert.source.oci.OciMonitoringProperties
import com.wafflestudio.alert.source.oci.OciMysqlDbSystemProperties
import com.wafflestudio.alert.source.oci.OciMysqlMetricQuery
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

class OciMonitoringScheduler(
    private val adapter: OciMonitoringAdapter,
    private val evaluator: OciResourceEvaluator,
    private val ingestionService: AlertIngestionService,
    private val properties: OciMonitoringProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${alert.oci-monitoring.polling-interval-ms:60000}")
    fun poll() {
        properties.mysql.dbSystems
            .filter { it.enabled }
            .forEach(::pollDbSystem)
    }

    private fun pollDbSystem(dbSystem: OciMysqlDbSystemProperties) {
        try {
            val observations =
                adapter.fetchMysqlCpuUtilization(
                    OciMysqlMetricQuery(
                        compartmentId = dbSystem.compartmentId,
                        dbSystemId = dbSystem.id,
                        window = properties.queryWindow,
                        resolution = properties.resolution,
                    ),
                )
            val threshold = dbSystem.thresholds.cpuUtilization
            observations.forEach { observation ->
                evaluator
                    .evaluateCpuUtilization(
                        observation = observation,
                        threshold = CpuUtilizationThreshold(threshold.warning, threshold.critical),
                        context = OciAlertContext(dbSystem.service, dbSystem.team),
                    )?.let(ingestionService::ingest)
            }
        } catch (exception: Exception) {
            log.error("Failed to poll OCI MySQL DB system id={}", dbSystem.id, exception)
        }
    }
}
