package com.wafflestudio.alert.outbound.notification.routing

import com.wafflestudio.alert.config.DiscordProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource

/** 실제 src/main/resources/application.yml의 채널 매핑이 지켜야 하는 규칙. */
class TeamMappingConfigTest {
    private val binder =
        YamlPropertySourceLoader()
            .load("application", FileSystemResource("src/main/resources/application.yml"))
            .first()
            .let { Binder(listOfNotNull(ConfigurationPropertySource.from(it))) }
    private val namespaces = binder.bind("alert.team-mapping", Bindable.ofInstance(TeamMappingConfig())).get().namespaces
    private val channelIds = binder.bind("discord", Bindable.ofInstance(DiscordProperties())).get().channelIds

    @Test
    fun `서비스팀 dev의 infra alert는 prod와 같은 팀 infra 채널로 간다`() {
        val devNamespaces = namespaces.keys.filter { it.endsWith("-dev") }

        assertTrue(devNamespaces.isNotEmpty())
        devNamespaces.forEach { dev ->
            val prod = dev.removeSuffix("-dev") + "-prod"
            assertEquals(namespaces.getValue(prod).infra, namespaces.getValue(dev).infra, dev)
        }
    }

    @Test
    fun `app-alert에는 Loki 외의 alert가 가지 않는다`() {
        val infraToAppAlert = namespaces.filterValues { it.infra == "app-alert" }.keys

        assertEquals(emptySet<String>(), infraToAppAlert)
    }

    @Test
    fun `매핑된 채널 키는 모두 discord channel-ids에 있다`() {
        val missing =
            namespaces
                .flatMap { (namespace, channels) -> listOfNotNull(channels.app, channels.infra).map { namespace to it } }
                .filterNot { (_, key) -> key in channelIds }

        assertEquals(emptyList<Pair<String, String>>(), missing)
    }
}
