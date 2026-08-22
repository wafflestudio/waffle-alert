package com.wafflestudio.alert.domain.model

enum class AlertSource {
    ALERTMANAGER,
    OCI_COST,
    OCI_MONITORING,

    /** 쿠버네티스 API watch (Pod 실패 / CronJob 실패 / Node 변경). 구 k8s-monitoring. */
    K8S,
}

enum class AlertStatus {
    FIRING,
    RESOLVED,
    REPEATED,
}

enum class Severity {
    INFO,
    WARNING,
    CRITICAL,
}
