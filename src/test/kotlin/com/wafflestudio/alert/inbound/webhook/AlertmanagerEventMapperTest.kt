package com.wafflestudio.alert.inbound.webhook

import com.wafflestudio.alert.inbound.webhook.dto.AlertmanagerAlert
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlertmanagerEventMapperTest {
    private val mapper = AlertmanagerEventMapper()

    @Test
    fun `trace_id annotation이 있으면 AlertEvent traceId로 채운다`() {
        val alert =
            AlertmanagerAlert(
                status = "firing",
                labels = mapOf("alertname" to "ApplicationErrorLog", "namespace" to "siksha-prod"),
                annotations = mapOf("trace_id" to "abc-123"),
                fingerprint = "fp1",
            )

        val event = mapper.toAlertEvent(alert)

        assertEquals("abc-123", event.traceId)
    }

    @Test
    fun `trace_id annotation이 none이면 AlertEvent traceId도 none 그대로 전달한다`() {
        val alert =
            AlertmanagerAlert(
                status = "firing",
                labels = mapOf("alertname" to "ApplicationErrorLog", "namespace" to "siksha-prod"),
                annotations = mapOf("trace_id" to "none"),
                fingerprint = "fp2",
            )

        val event = mapper.toAlertEvent(alert)

        assertEquals("none", event.traceId)
    }

    @Test
    fun `trace_id annotation이 없는 기존 Prometheus alert는 traceId가 null이다`() {
        val alert =
            AlertmanagerAlert(
                status = "firing",
                labels = mapOf("alertname" to "PodMemoryLimitHigh", "namespace" to "siksha-prod"),
                annotations = mapOf("summary" to "memory high"),
                fingerprint = "fp3",
            )

        val event = mapper.toAlertEvent(alert)

        assertNull(event.traceId)
    }
}
