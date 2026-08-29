package com.wafflestudio.alert.source.k8s

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** `alert.k8s.enabled=true` 일 때만 watch 관련 빈을 만든다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(prefix = "alert.k8s", name = ["enabled"], havingValue = "true")
annotation class ConditionalOnK8sWatch
