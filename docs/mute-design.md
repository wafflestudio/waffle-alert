# Mute 기능 설계 (계획 문서, 미구현)

> 2026-09-08 기준 계획 문서. 아래 내용은 아직 구현되지 않았다. 실제 작업 순서와 범위는
> [7. 단계별 로드맵](#7-단계별-로드맵)을 따른다.

슬랙에서 나온 요구:

- FIRING만 오게 하고 RESOLVED/REPEATED 노이즈를 줄이고 싶다.
- 같은 에러 로그가 반복 감지되는 걸 줄이고 싶다.
- 특정 키워드가 들어간 에러를 일정 시간(5분/1시간/24시간 등) 동안 mute하고 싶다.
- `/mute <>` 같은 명령어, 또는 Discord 메시지의 이모지/버튼 인터랙션으로 mute를 걸고 싶다.
- 과거 Slack 봇에서는 "버튼을 누르면 그 메시지가 담고 있던 특정 문자열을 뮤트"하는 방식을 썼다.

## 1. 핵심 판단: alert 종류마다 mute의 의미가 다르다

waffle-alert가 다루는 alert는 성격이 둘로 나뉜다 ([architecture.md](./architecture.md) 참고).

| | Prometheus/K8s watch (워크로드) | Loki `ApplicationErrorLog` (애플리케이션 에러) |
| --- | --- | --- |
| 판단 주체 | Alertmanager (이미 상태 있음) | waffle-alert (Loki에서 로그 원문을 별도 조회) |
| 식별 단위 | alert rule + label (`alertname`, `namespace`, `pod` 등) | `namespace + pod + app` — **에러 메시지 원문은 label에 없다** |
| "같은 문제"의 의미 | label 조합이 같으면 같은 문제 | label 조합이 같아도 로그 원문(에러 메시지)은 매번 다를 수 있다 |

`ApplicationErrorLog` 룰 정의([waffle-world-oci/argocd/loki/resources.yaml](../../waffle-world-oci/argocd/loki/resources.yaml)):

```yaml
expr: |
  sum by (namespace, pod, app) (
    count_over_time({always="1"}[2m])
  ) > 0
```

이 룰은 `namespace/pod/app`에서 에러 로그가 하나라도 있으면 발동한다. 즉 **fingerprint에 실제
에러 메시지가 반영되지 않는다.** `DB timeout`이 나든 `NullPointerException`이 나든 같은
pod라면 같은 fingerprint. 실제 에러 문자열은 `DiscordNotificationAdapter.lokiContextSuffix`가
Discord로 보내기 직전에 Loki(`LokiClient.fetchLogLines`)를 다시 조회해서 로그 원문 자체에서만
얻는다.

**결론: 두 종류는 mute 구현 방식이 달라야 한다.**

- **Prometheus/K8s watch**: label(=fingerprint 구성 요소) 기준 mute면 충분 → Alertmanager가
  이미 제공하는 표준 기능을 그대로 쓴다.
- **Loki 애플리케이션 에러**: label만으로는 "특정 에러 문자열"을 구분할 수 없다 → 로그 원문에
  대한 키워드 매칭이 필요하고, 이건 Alertmanager가 대신해줄 수 없으므로 waffle-alert가 직접
  구현해야 한다.

## 2. Prometheus/K8s watch mute → Alertmanager Silence API를 그대로 쓴다

재발명하지 않는다. Alertmanager는 이미 label 기반 억제 기능을 표준으로 제공한다.

- `POST /api/v2/silences` — matcher(`name`, `value`, `isRegex`, `isEqual`) 배열 + `startsAt`/
  `endsAt`(TTL)로 silence 생성. 여러 matcher는 AND로 결합된다.
- `DELETE /api/v2/silence/{id}` — 조기 해제.
- `GET /api/v2/silences?filter=...` — 현재 활성 silence 조회.

waffle-alert가 할 일은 **Alertmanager API를 대신 호출해주는 얇은 클라이언트**뿐이다.

```
/mute alertname:PodMemoryLimitHigh namespace:siksha-prod duration:1h
  -> waffle-alert가 POST /api/v2/silences 호출
       matchers: [{name: alertname, value: "PodMemoryLimitHigh", isRegex: false},
                  {name: namespace, value: "siksha-prod", isRegex: false}]
       endsAt: now + 1h
  -> Alertmanager가 이후 매칭되는 alert를 webhook으로 아예 보내지 않음
     (waffle-alert의 AlertmanagerWebhookController는 호출조차 안 됨)
```

K8s watch(`AlertSource.K8S`)는 Alertmanager를 거치지 않고 waffle-alert가 K8s API를 직접
watch하므로 이 경로에는 안 걸린다. K8s watch mute는 3장의 자체 mute_rules로 함께 처리한다
(fingerprint 접두사가 `k8s/`로 시작하는 것으로 구분 가능 — [K8sEventMapper.kt](../src/main/kotlin/com/wafflestudio/alert/source/k8s/K8sEventMapper.kt)).

### 필요한 것

- Alertmanager의 클러스터 내부 주소 (`prometheus` 네임스페이스, kube-prometheus-stack이 만드는
  Service — 정확한 이름은 `argocd/prometheus` Helm release 확인 필요. 예:
  `{release-name}-kube-prometheus-alertmanager.prometheus.svc.cluster.local:9093`).
- `AlertmanagerSilenceClient` (신규): `RestClient` 기반 얇은 wrapper. Alertmanager silence
  request/response DTO, `createSilence(matchers, duration)`/`deleteSilence(id)`.

## 3. Loki 애플리케이션 에러 mute → waffle-alert 자체 구현

### 데이터 모델

```
mute_rules
  id            BIGINT PK
  namespace     VARCHAR(128)   NOT NULL   -- 팀 채널 스코프. 서비스팀이 자기 네임스페이스만 mute
  keyword       VARCHAR(512)   NOT NULL   -- 로그 원문에 포함되면 매칭 (단순 substring, 정규식은 후속)
  created_by    VARCHAR(128)   NULL       -- Discord user id/tag. 누가 걸었는지 추적
  created_at    DATETIME(6)    NOT NULL
  expires_at    DATETIME(6)    NOT NULL   -- TTL. NULL 허용 안 함 - 무기한 mute는 사고 위험이 큼
  UNIQUE KEY (namespace, keyword)
```

과거 슬랙 봇 패턴("버튼 누르면 메시지가 담고 있던 문자열을 그대로 mute")을 그대로 가져온다.
`keyword`는 처음엔 사람이 직접 입력한 substring 매칭만 지원한다(정규식은 오탐 위험이 커서
후속 단계로 미룬다).

### 판단 지점

`DiscordNotificationAdapter.formatMessage` → `lokiContextSuffix`가 `lokiClient.fetchLogLines()`로
로그 원문을 이미 조회하고 있다 ([DiscordNotificationAdapter.kt:120-134](../src/main/kotlin/com/wafflestudio/alert/outbound/notification/DiscordNotificationAdapter.kt)).
이 조회 결과를 얻은 직후, Discord로 보내기 직전에 mute 여부를 판단한다. 추가 네트워크 호출 없이
기존 흐름에 검증 단계만 끼워 넣는다.

```kotlin
override fun notify(event: AlertEvent): Boolean {
    ...
    if (channelType == ChannelType.APPLICATION) {
        val logLines = lokiClient.fetchLogLines(event.service, event.observedAt)
        if (muteService.isMuted(event.service, logLines)) {
            log.info("Muted, skip notify (namespace={}, fingerprint={})", event.service, event.fingerprint)
            return true // 실패가 아니라 "의도적으로 안 보냄"
        }
        ...
    }
}
```

`MuteService.isMuted(namespace, logLines)`는 해당 namespace의 활성(만료 안 된) `mute_rules`를
전부 가져와 `logLines.any { line -> rules.any { line.contains(it.keyword) } }`로 판단한다.
namespace별 rule 수는 적을 것으로 예상되므로 매 알림마다 DB 조회해도 무리 없다(추후 캐싱은
필요해지면).

로그 원문을 두 번 조회(mute 판단용, 실제 메시지 첨부용)하지 않도록 `fetchLogLines` 결과를
그대로 재사용하는 게 중요하다 — 현재 코드처럼 판단 로직과 메시지 조립 로직을 분리하되 조회는
한 번만 한다.

## 4. 트리거 인터페이스: Discord Slash Command

이모지/버튼 인터랙션이 아니라 `/mute` 슬래시 커맨드를 1차로 만든다 (팀 논의에서 결정).
버튼 방식("이 알림 뮤트" 버튼을 메시지에 항상 붙이는 방식)은 이후 필요해지면 추가한다 — 커맨드
파싱 로직(파라미터 검증, Alertmanager/mute_rules 분기)을 재사용할 수 있어 큰 추가 비용은 아니다.

### Discord 쪽 요구사항

Discord 슬래시 커맨드는 두 경로가 있다: Gateway(WebSocket 상시 연결) 또는 HTTP Interactions
Endpoint. waffle-alert는 현재 상시 연결이 없는 요청-응답형 REST 서버이므로 **HTTP Interactions
Endpoint**를 택한다.

1. **커맨드 등록**: Discord REST API `PUT /applications/{app_id}/commands`로 `/mute`의 이름/
   옵션(예: `scope`(app/workload), `keyword` 또는 `alertname`, `duration`)을 한 번 등록.
2. **Interactions Endpoint URL 등록**: Discord Developer Portal에서 waffle-alert의 공개
   HTTPS 엔드포인트를 등록해야 한다. 등록 시 Discord가 `PING`(`type=1`)을 보내고 우리 서버는
   `PONG`(`type=1`)으로 즉시 응답해야 등록이 완료된다.
3. **요청 검증**: 모든 요청에 `X-Signature-Ed25519`/`X-Signature-Timestamp` 헤더가 실려온다.
   raw body + timestamp를 Discord Application의 Public Key로 Ed25519 서명 검증해야 한다 —
   실패하면 401을 반환해야 하고, 이게 없으면 Discord가 엔드포인트 등록 자체를 거부한다.
4. **응답 시간 제한**: 3초 안에 최초 응답을 줘야 한다. DB 조회+Alertmanager 호출이 3초를
   넘길 수 있으므로 `deferred` 응답(`type=5`) 후 후속 webhook으로 최종 결과를 보내는 패턴을
   쓴다.

### 새로 필요한 컴포넌트

```
inbound/discord
  DiscordInteractionController   -- POST /api/v1/discord/interactions, PING/PONG, 서명 검증
  DiscordSignatureVerifier       -- Ed25519 검증 (Bouncy Castle 등)
  MuteCommandHandler             -- /mute 옵션 파싱 -> scope별로 AlertmanagerSilenceClient
                                     또는 MuteService.create 호출
```

## 5. 인프라 선행조건 (waffle-world-oci, 별도 작업)

`argocd/waffle-alert-prod/waffle-alert.yaml`은 현재 ClusterIP Service만 있고, 외부 노출용
VirtualService는 주석 처리돼 있다:

```yaml
# Alertmanager(클러스터 내부)가 webhook을 쏘는 경로는 ClusterIP Service로 충분하므로
# 외부 노출(VirtualService)은 필수가 아니다. 대시보드/외부 접근이 필요해지면 아래를 활성화한다.
```

Discord Interactions Endpoint는 Discord 서버가 우리 서버를 인터넷에서 직접 HTTPS로 호출해야
동작하므로, **`/mute` 슬래시 커맨드가 실제로 동작하려면 이 VirtualService(+ TLS, 도메인)를
활성화하는 인프라 작업이 선행되어야 한다.** waffle-alert 레포의 코드만으로는 완결되지 않는다.

이 작업은 waffle-world-oci 레포에서 별도로 진행한다 (이번 계획 문서 범위 밖).

- `waffle-alert.wafflestudio.com` 같은 서브도메인 노출.
- Istio Gateway 인증서(cert-manager로 기존 패턴 재사용 가능해 보임 — 다른 서비스들의
  VirtualService 참고).
- Discord Interactions Endpoint URL 등록은 이 도메인이 살아있어야 Discord Developer Portal에서
  검증(PING/PONG)을 통과한다.

## 6. RESOLVED/REPEATED 노이즈 감소 (별도 논의)

슬랙에서 나온 "1. warn이랑 resolved는 끄는 게 어떨까요", "Resolved-Firing 굳이 필요없을 수도"는
mute와는 결이 다른 요구다 — 특정 alert를 일시적으로 억제하는 게 아니라 **알림 정책 자체**를
바꾸는 것. 이건:

- WARNING severity를 기본은 보내지 않고 팀 요청 시 전용 채널 생성 (슬랙에서 이미 논의됨).
- RESOLVED 메시지를 아예 안 보내거나 별도 옵션화.

는 mute_rules와 무관하게 `DiscordNotificationAdapter`/`ChannelType` 레벨의 정책 변경이라
별도 이슈로 다룬다. 이번 문서는 "특정 alert/키워드를 일정 시간 억제"하는 mute 기능에 한정한다.

## 7. 단계별 로드맵

인프라(§5)가 준비되기 전까지 `/mute` 커맨드 자체는 동작할 수 없다. 그 전에 미리 만들 수 있는
부분부터 순서대로 진행한다.

1. **(현재 단계) 계획 문서화** — 이 문서.
2. **mute 데이터모델 + 적용 로직** (인프라 무관, 지금 시작 가능)
   - `mute_rules` 테이블 + Flyway 마이그레이션.
   - `MuteService` (등록/조회/만료 판단).
   - `DiscordNotificationAdapter`에 mute 체크 삽입.
   - 임시 검증용 REST 엔드포인트(`POST /api/v1/mute` 등, `DiscordTestController`류)로
     curl 기반 수동 검증 — Discord 커맨드 없이도 로직 자체는 검증 가능.
3. **Alertmanager Silence 클라이언트** (인프라 무관)
   - `AlertmanagerSilenceClient` + Prometheus/K8s watch mute 연동.
4. **(선행조건) waffle-world-oci: VirtualService 노출 + TLS/도메인** — 별도 레포/PR.
5. **Discord Interactions Endpoint** (4 완료 후)
   - 서명 검증, PING/PONG, `/mute` 커맨드 등록, `MuteCommandHandler`.
   - 2, 3에서 만든 서비스를 커맨드 핸들러가 호출하도록 연결.
6. **(선택) 메시지 버튼/이모지 인터랙션** — 커맨드가 안정화된 후, 필요하면 추가.
