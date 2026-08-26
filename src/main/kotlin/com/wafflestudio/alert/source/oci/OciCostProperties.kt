package com.wafflestudio.alert.source.oci

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

@Configuration
@ConfigurationProperties(prefix = "alert.oci-cost")
class OciCostProperties {
    var spike: Spike = Spike()

    class Spike {
        var settleLagDays: Int = 1
        var warningMultiplier: BigDecimal = BigDecimal("1.5")
        var criticalMultiplier: BigDecimal = BigDecimal("2.0")
        var minAverageAmount: BigDecimal = BigDecimal("0.5")
    }
}
