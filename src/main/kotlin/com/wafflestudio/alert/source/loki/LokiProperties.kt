package com.wafflestudio.alert.source.loki

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/** alert.loki.* — Loki HTTP API 조회 및 Grafana Explore 링크 생성 설정 */
@Configuration
@ConfigurationProperties(prefix = "alert.loki")
class LokiProperties {
    var baseUrl: String = "http://loki.loki.svc.cluster.local:3100"
    var grafanaBaseUrl: String = "https://grafana.wafflestudio.com"
    var queryWindowMinutes: Long = 5
    var maxLines: Int = 50
}
