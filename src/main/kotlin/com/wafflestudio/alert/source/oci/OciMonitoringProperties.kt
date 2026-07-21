package com.wafflestudio.alert.source.oci

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("alert.oci-monitoring")
data class OciMonitoringProperties(
    val enabled: Boolean = false,
    val region: String = "ap-chuncheon-1",
    val pollingIntervalMs: Long = 60_000,
    val queryWindow: Duration = Duration.ofMinutes(5),
    val resolution: String = "1m",
    val mysql: OciMysqlMonitoringProperties = OciMysqlMonitoringProperties(),
)

data class OciMysqlMonitoringProperties(
    val dbSystems: Map<String, OciMysqlDbSystemProperties> = emptyMap(),
)

data class OciMysqlDbSystemProperties(
    val id: String,
    val compartmentId: String,
    val service: String? = null,
    val team: String? = null,
    val enabled: Boolean = true,
    val thresholds: OciMysqlThresholdProperties,
)

data class OciMysqlThresholdProperties(
    val cpuUtilization: OciThresholdProperties,
    val memoryUtilization: OciThresholdProperties,
    val currentConnections: OciThresholdProperties,
    val activeConnections: OciThresholdProperties,
    val dbVolumeUtilization: OciThresholdProperties,
)

data class OciThresholdProperties(
    val warning: Double,
    val critical: Double,
)
