-- alert_incidents/alert_event_logs는 IncidentService가 미구현 상태로 남아 실제 저장 경로가 없다.
-- 알림은 여전히 ingest() -> NotificationPort로 바로 나간다. 사용하지 않는 테이블을 정리한다.
DROP TABLE IF EXISTS alert_event_logs;
DROP TABLE IF EXISTS alert_incidents;
