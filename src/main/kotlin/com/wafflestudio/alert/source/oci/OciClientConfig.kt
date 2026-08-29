package com.wafflestudio.alert.source.oci

import com.oracle.bmc.ConfigFileReader
import com.oracle.bmc.Region
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider
import com.oracle.bmc.monitoring.MonitoringClient
import com.oracle.bmc.usageapi.UsageapiClient
import com.wafflestudio.alert.domain.evaluator.ResourceMetricEvaluator
import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.scheduler.OciMonitoringScheduler
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OciMonitoringProperties::class)
class OciClientConfig {
    @Bean
    fun ociAuthProvider(props: OciProperties): BasicAuthenticationDetailsProvider =
        when (props.authType) {
            OciProperties.AuthType.CONFIG_FILE -> {
                val configFile = ConfigFileReader.parse(props.configFilePath, props.profile)
                ConfigFileAuthenticationDetailsProvider(configFile)
            }

            OciProperties.AuthType.INSTANCE_PRINCIPAL ->
                InstancePrincipalsAuthenticationDetailsProvider.builder().build()
        }

    @Bean
    fun usageapiClient(
        provider: BasicAuthenticationDetailsProvider,
        props: OciProperties,
    ): UsageapiClient =
        UsageapiClient
            .builder()
            .region(Region.fromRegionId(props.region))
            .build(provider)

    @Bean
    @ConditionalOnProperty(prefix = "alert.oci-monitoring", name = ["enabled"], havingValue = "true")
    fun monitoringClient(
        provider: BasicAuthenticationDetailsProvider,
        properties: OciMonitoringProperties,
    ): MonitoringClient =
        MonitoringClient
            .builder()
            .region(properties.region)
            .build(provider)

    @Bean
    @ConditionalOnProperty(prefix = "alert.oci-monitoring", name = ["enabled"], havingValue = "true")
    fun ociMonitoringAdapter(
        monitoringClient: MonitoringClient,
        properties: OciMonitoringProperties,
    ): OciMonitoringAdapter = OciMonitoringAdapter(monitoringClient, properties.region)

    @Bean
    @ConditionalOnProperty(prefix = "alert.oci-monitoring", name = ["enabled"], havingValue = "true")
    fun ociMonitoringScheduler(
        adapter: OciMonitoringAdapter,
        evaluator: ResourceMetricEvaluator,
        ingestionService: AlertIngestionService,
        properties: OciMonitoringProperties,
    ): OciMonitoringScheduler = OciMonitoringScheduler(adapter, evaluator, ingestionService, properties)
}
