package com.wafflestudio.alert.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.wafflestudio.alert.source.loki.LokiProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class WebClientConfig(
    private val discordProperties: DiscordProperties,
    private val lokiProperties: LokiProperties,
) {
    // Spring Boot 4.0부터 Jackson 3(JsonMapper)이 기본 auto-configuration 대상이라,
    // 이 프로젝트가 여전히 쓰는 Jackson 2.x의 com.fasterxml.jackson.databind.ObjectMapper는
    // 더 이상 자동으로 빈 등록되지 않는다(LokiClient가 이 타입을 요구해 컨텍스트 로딩 실패로
    // 실제 CI에서 확인됨). 프로젝트 전체를 Jackson 3로 옮기는 대신 필요한 빈만 명시 등록.
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()

    @Bean
    fun discordRestClient(): RestClient =
        RestClient
            .builder()
            .baseUrl("https://discord.com/api/v10")
            .defaultHeader("Authorization", "Bot ${discordProperties.botToken}")
            .defaultHeader("Content-Type", "application/json")
            .build()

    @Bean
    fun lokiRestClient(): RestClient =
        RestClient
            .builder()
            .baseUrl(lokiProperties.baseUrl)
            .build()

    // another infra client bean can added in here
}
