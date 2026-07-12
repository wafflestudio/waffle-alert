package com.wafflestudio.alert.domain.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/** alert_incidents.labels / alert_event_logs.labels (JSON 컬럼) <-> Map<String, String> */
@Converter
class StringMapJsonConverter : AttributeConverter<Map<String, String>, String> {
    private val objectMapper = jacksonObjectMapper()

    override fun convertToDatabaseColumn(attribute: Map<String, String>?): String =
        objectMapper.writeValueAsString(attribute ?: emptyMap<String, String>())

    override fun convertToEntityAttribute(dbData: String?): Map<String, String> =
        if (dbData.isNullOrBlank()) {
            emptyMap()
        } else {
            objectMapper.readValue(
                dbData,
                objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java),
            )
        }
}
