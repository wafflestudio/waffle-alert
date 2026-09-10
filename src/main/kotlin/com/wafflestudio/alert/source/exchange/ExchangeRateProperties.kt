package com.wafflestudio.alert.source.exchange

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "alert.exchange-rate")
class ExchangeRateProperties {
    var baseUrl: String = "https://api.frankfurter.dev"
    var cacheTtlHours: Long = 12
    var failureRetryMinutes: Long = 30
}
