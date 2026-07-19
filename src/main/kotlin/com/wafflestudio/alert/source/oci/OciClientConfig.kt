package com.wafflestudio.alert.source.oci

import com.oracle.bmc.auth.AuthenticationDetailsProvider
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider
import com.oracle.bmc.monitoring.MonitoringClient
import com.wafflestudio.alert.domain.evaluator.ResourceMetricEvaluator
import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.scheduler.OciMonitoringScheduler
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OciMonitoringProperties::class)
@ConditionalOnProperty(
    prefix = "alert.oci-monitoring",
    name = ["enabled"],
    havingValue = "true",
)
class OciClientConfig {
    @Bean
    fun monitoringClient(
        properties: OciMonitoringProperties,
        environment: Environment,
    ): MonitoringClient =
        MonitoringClient
            .builder()
            .region(properties.region)
            .build(authenticationProvider(environment))

    @Bean
    fun ociMonitoringAdapter(
        monitoringClient: MonitoringClient,
        properties: OciMonitoringProperties,
    ): OciMonitoringAdapter = OciMonitoringAdapter(monitoringClient, properties.region)

    @Bean
    fun ociMonitoringScheduler(
        adapter: OciMonitoringAdapter,
        evaluator: ResourceMetricEvaluator,
        ingestionService: AlertIngestionService,
        properties: OciMonitoringProperties,
    ): OciMonitoringScheduler =
        OciMonitoringScheduler(adapter, evaluator, ingestionService, properties)

    private fun authenticationProvider(environment: Environment): AuthenticationDetailsProvider {
        val authType = environment.getProperty("oci.auth.type", "instance_principal").trim().lowercase()
        return when (authType) {
            "config", "configfile", "config_file", "config-file" -> configFileAuthenticationProvider(environment)
            "instance_principal", "instanceprincipal", "instance-principal", "ip" ->
                InstancePrincipalsAuthenticationDetailsProvider.builder().build()
            else ->
                throw IllegalArgumentException(
                    "Unsupported oci.auth.type='$authType'. Supported: config, instance_principal",
                )
        }
    }

    private fun configFileAuthenticationProvider(environment: Environment): AuthenticationDetailsProvider {
        val profile = environment.getProperty("oci.config.profile", "DEFAULT").trim().ifEmpty { "DEFAULT" }
        val configPath =
            environment
                .getProperty("oci.config.path")
                ?.trim()
                ?.ifEmpty { null }
                ?.let(::expandHome)

        return if (configPath == null) {
            ConfigFileAuthenticationDetailsProvider(profile)
        } else {
            ConfigFileAuthenticationDetailsProvider(configPath, profile)
        }
    }

    private fun expandHome(path: String): String {
        val home = System.getProperty("user.home")
        return if (path == "~") home else path.replaceFirst(Regex("^~(?=/|$)"), home)
    }
}
