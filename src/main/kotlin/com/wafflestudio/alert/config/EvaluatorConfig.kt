package com.wafflestudio.alert.config

import com.wafflestudio.alert.domain.evaluator.ResourceMetricEvaluator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class EvaluatorConfig {
    @Bean
    fun resourceMetricEvaluator(): ResourceMetricEvaluator = ResourceMetricEvaluator()
}
