package com.wafflestudio.alert.source.oci

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider
import com.oracle.bmc.monitoring.MonitoringClient
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
}
