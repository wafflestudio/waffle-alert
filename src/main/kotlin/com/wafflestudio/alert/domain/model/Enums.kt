package com.wafflestudio.alert.domain.model

enum class AlertSource {
    ALERTMANAGER,
    OCI_COST,
    OCI_MONITORING,
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
