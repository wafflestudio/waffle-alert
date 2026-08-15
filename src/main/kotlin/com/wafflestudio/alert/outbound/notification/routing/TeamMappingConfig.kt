package com.wafflestudio.alert.outbound.notification.routing

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/** alert.team-mapping.* — namespace -> discord.channel-ids 키 매핑을 application.yml에서 바인딩 */
@Configuration
@ConfigurationProperties(prefix = "alert.team-mapping")
class TeamMappingConfig {
    var namespaceToChannel: Map<String, String> = emptyMap()
}
