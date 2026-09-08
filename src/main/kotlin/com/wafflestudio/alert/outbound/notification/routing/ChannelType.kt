package com.wafflestudio.alert.outbound.notification.routing

/** namespace 매핑을 조회할 때 어떤 성격의 alert인지 구분한다. */
enum class ChannelType {
    /** Loki 기반 애플리케이션 에러 로그(ApplicationErrorLog). */
    APPLICATION,

    /** Prometheus/K8s watch 등 워크로드·인프라 상태 alert. */
    WORKLOAD,
}
