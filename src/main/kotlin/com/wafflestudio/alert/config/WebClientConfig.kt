package com.wafflestudio.alert.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class WebClientConfig(
    private val discordProperties: DiscordProperties,
) {
    @Bean
    fun discordRestClient(): RestClient =
        RestClient
            .builder()
            .baseUrl("https://discord.com/api/v10")
            .defaultHeader("Authorization", "Bot ${discordProperties.botToken}")
            .defaultHeader("Content-Type", "application/json")
            .build()

    // another infra client bean can added in here
}
