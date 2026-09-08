package com.wafflestudio.alert.domain.service

import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.outbound.notification.NotificationPort
import org.springframework.stereotype.Service

@Service
class AlertIngestionService(
    private val notificationPort: NotificationPort,
) {
    /** @return 알림 전송에 성공했으면 true (NotificationPort 참고). */
    fun ingest(event: AlertEvent): Boolean = notificationPort.notify(event)
}
