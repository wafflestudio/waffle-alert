package com.wafflestudio.alert.source.oci

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("alert.oci-monitoring")
data class OciMonitoringProperties(
    val enabled: Boolean = false,
    val region: String = "ap-chuncheon-1",
)
