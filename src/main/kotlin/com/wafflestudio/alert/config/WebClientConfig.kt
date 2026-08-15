package com.wafflestudio.alert.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.wafflestudio.alert.source.loki.LokiProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.util.DefaultUriBuilderFactory

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

    // LokiClient가 만드는 LogQL 쿼리 문자열은 `{namespace="..."}` 처럼 리터럴 중괄호를
    // 포함한다. RestClient의 기본 UriBuilderFactory(EncodingMode.TEMPLATE_AND_VALUES)는
    // 이 중괄호를 URI 템플릿 변수로 해석하려다 "Not enough variable values available to
    // expand"로 매 요청마다 실패했다(운영 로그로 실제 확인). VALUES_ONLY로 바꾸면 값에
    // 템플릿 문법을 적용하지 않고 그대로 퍼센트 인코딩만 하므로, 우리처럼 URI 템플릿을
    // 아예 안 쓰고 queryParam(name, value)로만 조립하는 경우엔 그대로 안전하게 대체된다.
    @Bean
    fun lokiRestClient(): RestClient {
        val uriBuilderFactory = DefaultUriBuilderFactory(lokiProperties.baseUrl)
        uriBuilderFactory.encodingMode = DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY
        return RestClient
            .builder()
            .uriBuilderFactory(uriBuilderFactory)
            .build()
    }

    // another infra client bean can added in here
}
