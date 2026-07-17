package com.wafflestudio.alert.source.oci

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider
import com.oracle.bmc.monitoring.MonitoringClient
import com.wafflestudio.alert.domain.evaluator.OciResourceEvaluator
import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.scheduler.OciMonitoringScheduler
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OciMonitoringProperties::class)
@ConditionalOnProperty(
    prefix = "alert.oci-monitoring",
    name = ["enabled"],
    havingValue = "true",
)
class OciClientConfig {
    @Bean
    fun monitoringClient(properties: OciMonitoringProperties): MonitoringClient =
        MonitoringClient
            .builder()
            .region(properties.region)
            .build(InstancePrincipalsAuthenticationDetailsProvider.builder().build())

    @Bean
    fun ociMonitoringAdapter(
        monitoringClient: MonitoringClient,
        properties: OciMonitoringProperties,
    ): OciMonitoringAdapter = OciMonitoringAdapter(monitoringClient, properties.region)

    @Bean
    fun ociResourceEvaluator(): OciResourceEvaluator = OciResourceEvaluator()

    @Bean
    fun ociMonitoringScheduler(
        adapter: OciMonitoringAdapter,
        evaluator: OciResourceEvaluator,
        ingestionService: AlertIngestionService,
        properties: OciMonitoringProperties,
    ): OciMonitoringScheduler =
        OciMonitoringScheduler(adapter, evaluator, ingestionService, properties)
}
