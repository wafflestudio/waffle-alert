package com.wafflestudio.alert.inbound.discord

import com.wafflestudio.alert.config.DiscordProperties
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class DiscordGatewayTest {
    private val jda = mockk<JDA>(relaxed = true)

    @Test
    fun `연결에 실패하면 예외를 던지지 않고 다시 시도한다`() {
        val attempts = AtomicInteger()
        val gateway =
            DiscordGateway(
                connect = {
                    if (attempts.incrementAndGet() < 3) error("discord down")
                    jda
                },
                minBackoff = Duration.ofMillis(10),
            )

        gateway.start()

        assertSame(jda, awaitConnected(gateway))
        assertEquals(3, attempts.get())
        gateway.destroy()
    }

    @Test
    fun `토큰이 틀렸으면 다시 시도하지 않는다`() {
        val attempts = AtomicInteger()
        val gateway =
            DiscordGateway(
                connect = {
                    attempts.incrementAndGet()
                    throw InvalidTokenException()
                },
                minBackoff = Duration.ofMillis(10),
            )

        gateway.start()
        Thread.sleep(200)

        assertEquals(1, attempts.get())
        assertNull(gateway.jda)
        gateway.destroy()
    }

    @Test
    fun `종료할 때 연결도 끊는다`() {
        val gateway = DiscordGateway(connect = { jda })
        gateway.start()
        awaitConnected(gateway)

        gateway.destroy()

        verify { jda.shutdown() }
    }

    /**
     * 빈을 만드는 동안에는 Discord에 연결하지 않는다 - 연결은 ApplicationReadyEvent 이후다.
     * 그래서 Discord가 안 돼도 컨텍스트는 뜬다. (ApplicationContextRunner는 ReadyEvent를 내지 않는다)
     */
    @Test
    fun `mute를 켜도 컨텍스트를 띄우는 동안에는 Discord에 연결하지 않는다`() {
        ApplicationContextRunner()
            .withPropertyValues("alert.mute.enabled=true")
            .withBean(DiscordProperties::class.java, { DiscordProperties().apply { botToken = "not-a-real-token" } })
            .withBean(MuteCommandHandler::class.java, { mockk<MuteCommandHandler>() })
            .withUserConfiguration(DiscordGatewayConfig::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                assertNull(context.getBean(DiscordGateway::class.java).jda)
            }
    }

    @Test
    fun `mute를 끄면 Gateway 빈이 없다`() {
        ApplicationContextRunner()
            .withPropertyValues("alert.mute.enabled=false")
            .withUserConfiguration(DiscordGatewayConfig::class.java)
            .run { context -> assertEquals(0, context.getBeanNamesForType(DiscordGateway::class.java).size) }
    }

    private fun awaitConnected(gateway: DiscordGateway): JDA {
        repeat(200) {
            gateway.jda?.let { return it }
            Thread.sleep(10)
        }
        return gateway.jda ?: error("gateway did not connect")
    }
}
