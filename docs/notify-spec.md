# Notify 단계 작업 명세 (AlertEvent → Discord)

> `AlertIncident`/`AlertEventLog`(DB 저장, fingerprint 묶기)는 이번 단계에서 보류.
> 이번 단계는 **각 source가 `AlertEvent`를 채워서 `AlertIngestionService`에 넘기면 Discord로 나간다**까지만 만든다.

## 1. 전체 흐름

```
[팀원 A] Alertmanager webhook   ──┐
[팀원 B] OCI Monitoring poller → ResourceMetricObservation → evaluator ─┼→ AlertEvent → AlertIngestionService.ingest(event) → NotificationPort → Discord
[팀원 C] OCI Cost poller        ──┘                            (공통, 준병 담당)
```

- 팀원 3명은 **각자 소스의 payload/API 응답을 `AlertEvent`로 변환해서 `AlertIngestionService.ingest(event)` 한 줄만 호출**하면 끝.
- `AlertIngestionService`는 지금은 `NotificationPort`를 바로 호출하지만, 추후 Incident/EventLog 처리가 그 앞단에 들어갈 자리다.
- 메시지 포맷팅, 채널 라우팅, role 멘션은 전부 공통 어댑터(`DiscordNotificationAdapter`)가 처리한다. 

## 2. 공통 계약: `AlertEvent`

source와 무관한 공통 payload. 지금은 notify에만 쓰지만, 필드는 나중 incident 저장까지 고려해 확정한다.

```kotlin
data class AlertEvent(
    val source: AlertSource,           // ALERTMANAGER / OCI_MONITORING / OCI_COST
    val status: AlertStatus,           // FIRING / RESOLVED / REPEATED
    val severity: Severity,            // INFO / WARNING / CRITICAL
    val fingerprint: String,
    val ruleName: String,
    val title: String,
    val description: String?,
    val service: String?,
    val team: String?,                 // 멘션할 role 결정에 사용
    val resourceType: String?,
    val resourceId: String?,
    val resourceName: String?,
    val metricName: String?,
    val metricStatistic: String?,
    val metricUnit: String?,
    val value: String?,
    val threshold: String?,
    val thresholdUnit: String?,
    val comparisonOperator: String?,
    val observedAt: Instant,
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    val rawPayload: String? = null,    // optional
)
```

각 source 담당자는 이 필드 중 **자기 소스에서 실제로 채울 수 있는 것만** 채우고 나머지는 null/기본값으로 둔다. 필수/선택 구분은 소스별 섹션 참고.

## 3. NotificationPort 시그니처

```kotlin
interface NotificationPort {
    fun notify(event: AlertEvent)
}
```

- `AlertIncident` 파라미터 제거. `AlertEvent` 하나만 받는다.
- 메시지 포맷팅(`formatMessage`)은 어댑터 내부에서 `AlertEvent` 필드로 조립 (source/status/severity/title/service/resource 등).

## 4. 채널 라우팅 & 멘션 — 공통 담당 (준병)

`DiscordNotificationAdapter` 내부 고정 매핑으로 처리. 팀원은 `source`/`team` 필드만 정확히 채우면 된다.

- **채널**: `event.source`를 `channelKeyOf(source)`로 `discord.channel-ids`(yml)의 키 문자열로 변환 → 해당 채널 ID로 전송.
  - `ALERTMANAGER` → `prometheus-alert`
  - `OCI_COST` → `oci-cost`
  - `OCI_MONITORING` → `oci-monitoring`
- **멘션**: `event.team` 문자열을 `mentionRoleOf(team)`으로 `DiscordMentionRole` enum과 매핑 → 매핑되면 메시지 앞에 `<@&roleId>` 멘션을 붙인다. 매핑 안 되는 team이면 멘션 없이 보내고 경고 로그만 남긴다.

## 5. 팀원 작업 위치 (패키지)

기존 hexagonal 구조의 `inbound` / `source` 아래 각자 자리. 공통 모델(`domain/model`)과 전송 계층(`outbound/notification`)은 건드릴 필요 없음(변경 필요 시 상의).

| 담당 | 위치 | 만들 것 |
| --- | --- | --- |
| Alertmanager | `inbound/webhook/` (+ `inbound/webhook/dto/`) | webhook payload 수신 컨트롤러, Alertmanager JSON → `AlertEvent` 매퍼 |
| OCI Monitoring | `source/oci/`, `source/scheduler/` | 주기 polling, API 응답 → `ResourceMetricObservation` 변환, rule 평가 → `AlertEvent` |
| OCI Cost | `source/oci/` | 일 단위 polling, OCI Cost API 클라이언트, 응답 → `AlertEvent` 매퍼 |

공통으로: 변환 후 `AlertIngestionService.ingest(event)` 호출 지점까지만 구현하면 각자 담당 끝.

## 6. 내(공통) 담당 작업 목록

- [x] `AlertEvent` 필드를 위 20개 스펙대로 확정
- [x] `NotificationPort.notify(incident, event)` → `notify(event)` 시그니처 변경
- [x] `DiscordNotificationAdapter`
  - [x] `formatMessage`를 `AlertIncident` 대신 `AlertEvent` 기준으로 재작성
  - [x] `channelKeyOf(source)` 유지 (그대로 재사용)
  - [x] `team → DiscordMentionRole` 매핑 테이블 추가, 메시지 앞에 멘션 붙이기
- [x] `AlertIngestionService.ingest(event)` 구현 — 현재는 `NotificationPort`를 바로 호출 (TODO: Incident/EventLog 구조 설계 필요)
- [x] `DiscordTestController` — `AlertIngestionService.ingest(event)` 호출로 정리
- [ ] 팀원들에게 이 문서 공유 + `AlertEvent` 필드/`AlertIngestionService` 시그니처 확정 알림
