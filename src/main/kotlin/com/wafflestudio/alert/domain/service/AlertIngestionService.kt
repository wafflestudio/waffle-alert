package com.wafflestudio.alert.domain.service

import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.outbound.notification.NotificationPort
import org.springframework.stereotype.Service

// TODO: Incident/EventLog 구조 설계 필요
//   AlertEvent -> IncidentService.upsert -> AlertEventLog 기록 -> ingest() 앞단에 추가
@Service
class AlertIngestionService(
    private val notificationPort: NotificationPort,
) {
    /** @return 알림 전송에 성공했으면 true (NotificationPort 참고). */
    fun ingest(event: AlertEvent): Boolean = notificationPort.notify(event)
}
