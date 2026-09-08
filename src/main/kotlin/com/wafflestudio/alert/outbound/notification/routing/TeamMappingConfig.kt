package com.wafflestudio.alert.outbound.notification.routing

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * alert.team-mapping.namespaces.* — namespace -> 팀의 discord.channel-ids 키 매핑을
 * application.yml에서 바인딩. 팀마다 애플리케이션 에러 로그 채널(app)과 워크로드 alert
 * 채널(infra)을 나눠 받을 수 있다.
 */
@Configuration
@ConfigurationProperties(prefix = "alert.team-mapping")
class TeamMappingConfig {
    var namespaces: Map<String, TeamChannels> = emptyMap()
}

class TeamChannels {
    var app: String? = null
    var infra: String? = null
}
