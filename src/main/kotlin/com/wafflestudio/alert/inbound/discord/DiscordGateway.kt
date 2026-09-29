package com.wafflestudio.alert.inbound.discord

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Discord Gateway 연결을 앱 기동과 떼어 놓는다.
 *
 * JDA의 build()는 토큰 확인을 위해 Discord REST를 동기로 호출하고 실패하면 예외를 던진다. 이걸 빈 생성
 * 중에 하면 Discord 장애나 네트워크 문제만으로 스프링 컨텍스트가 뜨지 않아 알림 전체가 멈춘다. 그래서
 * 앱이 뜬 뒤 별도 스레드에서 연결하고, 실패하면 backoff로 다시 시도한다. mute가 안 돼도 알림은 나간다.
 */
class DiscordGateway(
    private val connect: () -> JDA,
    private val minBackoff: Duration = Duration.ofSeconds(5),
    private val maxBackoff: Duration = Duration.ofMinutes(5),
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "discord-gateway-connect").apply { isDaemon = true } }

    @Volatile
    var jda: JDA? = null
        private set

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        executor.execute { tryConnect(attempt = 1) }
    }

    private fun tryConnect(attempt: Int) {
        try {
            jda = connect()
            log.info("Discord gateway login started (attempt={})", attempt)
        } catch (e: InvalidTokenException) {
            // 토큰이 틀렸으면 다시 해도 소용없다. 알림 전송도 같은 토큰을 쓰므로 그쪽 에러로도 드러난다.
            log.error("Discord bot token is invalid, /mute is disabled", e)
        } catch (e: Exception) {
            val delay = backoff(attempt)
            log.warn("Failed to connect Discord gateway, retry in {}s (attempt={})", delay.seconds, attempt, e)
            executor.schedule({ tryConnect(attempt + 1) }, delay.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    private fun backoff(attempt: Int): Duration {
        val multiplied = minBackoff.multipliedBy(1L shl (attempt - 1).coerceAtMost(16))
        return if (multiplied > maxBackoff) maxBackoff else multiplied
    }

    override fun destroy() {
        executor.shutdownNow()
        jda?.shutdown()
    }
}
