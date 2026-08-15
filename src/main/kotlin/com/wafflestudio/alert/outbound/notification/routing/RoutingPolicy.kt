package com.wafflestudio.alert.outbound.notification.routing

import org.springframework.stereotype.Component

/** namespace 기준으로 담당 팀 채널을 결정한다. 매핑에 없으면 null - 호출자가 기본 채널로 폴백한다. */
@Component
class RoutingPolicy(
    private val teamMappingConfig: TeamMappingConfig,
) {
    fun channelKeyForNamespace(namespace: String?): String? {
        if (namespace.isNullOrBlank()) return null
        return teamMappingConfig.namespaceToChannel[namespace]
    }
}
