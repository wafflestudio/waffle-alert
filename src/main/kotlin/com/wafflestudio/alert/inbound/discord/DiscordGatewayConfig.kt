package com.wafflestudio.alert.inbound.discord

import com.wafflestudio.alert.config.DiscordProperties
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * /mute 명령어를 받기 위한 Discord Gateway 연결. alert.mute.enabled=true일 때만 연결한다
 * (prod만 켠다 - 로컬/테스트에서 실제 Discord에 붙지 않게).
 *
 * 명령어 이벤트만 필요해서 createLight + 추가 intent 없이 연결해 멤버/메시지 캐시를 끈다.
 * JDA는 GUILDS intent를 항상 보내므로 서버 단위 명령어 등록에 필요한 서버 정보는 받는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "alert.mute", name = ["enabled"], havingValue = "true")
class DiscordGatewayConfig {
    @Bean
    fun muteCommandListener(
        handler: MuteCommandHandler,
        discordProperties: DiscordProperties,
    ): MuteCommandListener = MuteCommandListener(handler, discordProperties)

    @Bean(destroyMethod = "shutdown")
    fun jda(
        discordProperties: DiscordProperties,
        listener: MuteCommandListener,
    ): JDA =
        JDABuilder
            .createLight(discordProperties.botToken, emptyList())
            .addEventListeners(listener)
            .build()
}
