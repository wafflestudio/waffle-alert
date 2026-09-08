package com.wafflestudio.alert.inbound.webhook

import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.Severity
import com.wafflestudio.alert.domain.service.AlertIngestionService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** AlertEvent -> AlertIngestionService 연동 확인용 임시 엔드포인트. */
@RestController
class DiscordTestController(
    private val alertIngestionService: AlertIngestionService,
) {
    @PostMapping("/api/v1/alerts/discord/test")
    fun sendTestMessage(
        @RequestParam source: AlertSource,
        @RequestParam(required = false) team: String?,
        @RequestParam(required = false) namespace: String?,
        @RequestParam(required = false, defaultValue = "test-rule") ruleName: String,
    ): String {
        alertIngestionService.ingest(
            AlertEvent(
                source = source,
                status = AlertStatus.FIRING,
                severity = Severity.WARNING,
                fingerprint = "test-fingerprint-${System.currentTimeMillis()}",
                ruleName = ruleName,
                title = "hello infra",
                service = namespace,
                team = team,
            ),
        )
        return "sent"
    }
}
