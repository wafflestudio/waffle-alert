package com.wafflestudio.alert.config

import com.wafflestudio.alert.domain.model.AlertSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource

class MessageFormatPropertiesTest {
    /** 실제 src/main/resources/application.yml의 기본(첫 번째) 문서를 바인딩한다. */
    private val properties: MessageFormatProperties =
        YamlPropertySourceLoader()
            .load("application", FileSystemResource("src/main/resources/application.yml"))
            .first()
            .let { Binder(listOfNotNull(ConfigurationPropertySource.from(it))) }
            .bind("alert.message.meta-fields", Bindable.ofInstance(MessageFormatProperties()))
            .get()

    @Test
    fun `OCI Monitoring과 OCI Cost만 severity를 보여준다`() {
        assertEquals(listOf(MetaField.SEVERITY), properties.fieldsFor(AlertSource.OCI_MONITORING))
        assertEquals(listOf(MetaField.SEVERITY), properties.fieldsFor(AlertSource.OCI_COST))
    }

    @Test
    fun `나머지 source는 메타 필드를 보여주지 않는다`() {
        assertEquals(emptyList<MetaField>(), properties.fieldsFor(AlertSource.ALERTMANAGER))
        assertEquals(emptyList<MetaField>(), properties.fieldsFor(AlertSource.K8S))
    }
}
