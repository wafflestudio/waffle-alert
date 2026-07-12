package com.wafflestudio.alert.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/** discord.bot-token / discord.channel-ids (AlertSource 이름 -> 채널 ID) 를 application.yml에서 바인딩 */
@Configuration
@ConfigurationProperties(prefix = "discord")
class DiscordProperties {
    lateinit var botToken: String
    var channelIds: Map<String, String> = emptyMap()
}
