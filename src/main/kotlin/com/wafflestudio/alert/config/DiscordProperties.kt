package com.wafflestudio.alert.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Spring Environment의 discord.bot-token과 source별 discord.channel-ids를 바인딩한다. */
@Configuration
@ConfigurationProperties(prefix = "discord")
class DiscordProperties {
    var botToken: String = ""
    var channelIds: Map<String, String> = emptyMap()

    /** /mute 명령어를 등록할 서버. 비어 있으면 global 명령어로 등록한다. */
    var guildId: String = ""
}
