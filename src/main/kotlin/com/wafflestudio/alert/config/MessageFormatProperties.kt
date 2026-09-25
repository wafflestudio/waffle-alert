package com.wafflestudio.alert.config

import com.wafflestudio.alert.domain.model.AlertSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * alert.message.meta-fields — Discord 메시지에서 title 바로 아래 메타 줄(`severity: X · service: ...`)에
 * 어떤 필드를 보여줄지. 기본은 아무것도 보여주지 않고, 필요한 source만 켠다.
 */
@Configuration
@ConfigurationProperties(prefix = "alert.message.meta-fields")
class MessageFormatProperties {
    var default: List<MetaField> = emptyList()
    var sources: Map<AlertSource, List<MetaField>> = emptyMap()

    fun fieldsFor(source: AlertSource): List<MetaField> = sources[source] ?: default
}

/** 메타 줄에 표시할 수 있는 필드. 표시 순서는 선언 순서를 따른다. */
enum class MetaField {
    SEVERITY,
    SERVICE,
    RESOURCE,
}
