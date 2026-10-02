# Mute 기능 설계

> 2026-09-28 작성. 2026-09-08의 이전 설계(Alertmanager Silence + Loki 로그 키워드 매칭 +
> HTTP Interactions Endpoint)를 대체한다. 바뀐 이유는 [8. 이전 설계에서 바뀐 점](#8-이전-설계에서-바뀐-점) 참고.

알림 채널에서 이미 알고 있는 알림(배포 중인 deployment, 원인을 아는 반복 장애 등)을 정해진 시간 동안
받지 않게 한다.

## 1. 요구사항

- **채널 단위**: 명령어를 입력한 채널에만 적용한다. 다른 채널을 지정하는 옵션은 없다.
  `discord.channel-ids`에 등록된 알림 채널에서만 쓸 수 있다.
- **title 키워드 매칭**: title에 키워드가 들어간 알림을 보내지 않는다. 대소문자를 구분하지 않는 부분
  문자열 매칭이다. 소스(Loki, Prometheus, K8s watch, OCI)와 채널 종류(app, infra, 공용)에 상관없이
  같은 규칙이다.
  - title에 namespace와 리소스 이름이 들어 있으므로 deployment 이름(`hangsha-api`)만 적으면 그
    deployment의 모든 파드(`hangsha-api-7f9c8d-x2k4p`)가 걸린다.
  - 키워드는 선택이다. 비우면 채널 전체를 mute한다.
- **시간 필수**: `30m / 1h / 6h / 24h / 7d` 중에서 고른다. 최대 7일이고 무기한 mute는 없다.
- **일찍 해제**: `/unmute`로 해제한다. 이 채널의 활성 mute가 자동완성 목록으로 뜬다.
- **채널 표시**: 누가 입력했는지는 채널에 보이지 않는다. 봇이 결과만 짧은 영어 메시지로 남긴다.
  mute를 걸 때, 해제할 때, 만료될 때 모두 알린다.
- **권한**: 채널을 볼 수 있는 사람은 누구나 쓸 수 있다.

## 2. 명령어

```
/mute    duration: 30m | 1h | 6h | 24h | 7d (필수)    keyword: 텍스트 (선택)
/unmute  target: 자동완성 (이 채널의 활성 mute)
```

### 채널 메시지

```
Muted "hangsha-api" until 09-28 13:21 KST
Muted all alerts until 09-28 13:21 KST
Unmuted "hangsha-api"
Mute expired: "hangsha-api"
```

### 입력한 사람에게만 보이는 응답 (ephemeral)

`Done.`, `This command only works in alert channels.`, `No active mute found.`

슬래시 명령어에 공개로 답하면 Discord가 `{user} used /mute`를 자동으로 붙인다. 입력자를 숨기기 위해
명령어 응답은 ephemeral로만 하고, 채널 공지는 알림과 같은 방식(`POST /channels/{id}/messages`)의
일반 메시지로 따로 보낸다.

## 3. 데이터 모델

기존 `waffle_alert_prod` 스키마에 Flyway `V3__create_mute_rules.sql`로 추가한다. datasource, Vault,
Flyway 설정은 이미 운영에서 동작 중이라 따로 할 작업은 없다.

```sql
mute_rules (
  id          BIGINT PK AUTO_INCREMENT,
  channel_id  VARCHAR(32)  NOT NULL,             -- 명령어를 입력한 채널
  keyword     VARCHAR(200) NOT NULL DEFAULT '',  -- 소문자로 저장. '' = 채널 전체
  expires_at  DATETIME(6)  NOT NULL,
  created_by  VARCHAR(32)  NOT NULL,             -- Discord user id. 채널에는 표시하지 않는다
  created_at  DATETIME(6)  NOT NULL,
  ended_at    DATETIME(6)  NULL,                 -- 해제/만료 처리 시각
  end_reason  VARCHAR(16)  NULL,                 -- UNMUTED / EXPIRED
  ended_by    VARCHAR(32)  NULL,
  KEY (channel_id, ended_at, expires_at)
)
```

- 활성 mute: `ended_at IS NULL AND expires_at > now`
- 해제나 만료 때 행을 지우지 않고 `ended_*`만 채운다. 누가 무엇을 얼마나 mute했는지 추적할 수 있다.
- 같은 채널에 같은 키워드로 다시 걸면 새 행을 만들지 않고 `expires_at`만 갱신한다.

## 4. 알림 경로에서의 판단

`DiscordNotificationAdapter.notify()`에서 채널 ID가 정해진 직후, 메시지를 만들기 전에 확인한다.

```
RESOLVED면 건너뜀 → 채널 결정 → [mute 확인] → 메시지 조립 → 전송
```

- 메시지를 만들기 전에 확인하므로 mute된 Loki 알림은 로그 조회도 하지 않는다.
- mute됐으면 전송하지 않고 `true`(처리됨)를 반환한다. K8s 파드 알림은 이 값을 보고 alert-count를
  올리므로, mute가 풀린 뒤 같은 파드에 대해 다시 알림이 오지 않는다. 이미 인지한 장애라 자연스럽다.
- mute 확인이 실패하면(DB 오류 등) mute가 없는 것으로 보고 보낸다. 알림이 사라지는 쪽보다 낫다.
- 채널 ID 기준이라 공용 채널(infra-alert, oci-cost-alert 등)에도 그대로 동작한다.

## 5. Discord 연동

- waffle-alert가 이미 쓰는 봇 토큰으로 Gateway(WebSocket)에 연결한다. JDA를 쓴다.
  - 외부 노출(VirtualService, TLS)이 필요 없다. waffle-alert가 Discord로 연결을 먼저 연다.
  - 디코코(`discord-bot` namespace)와는 다른 봇이라 서로 영향이 없다.
  - `JDABuilder.createLight`에 추가 intent 없이 연결한다(GUILDS는 JDA가 항상 보낸다). 멤버/메시지
    캐시가 필요 없어서 메모리 증가를 최소화한다. voice 전용 의존성(`opus-java`, `tink`)은 뺀다.
  - **연결 실패가 알림을 막지 않게 한다.** JDA의 `build()`는 토큰 확인을 위해 Discord REST를 동기로
    호출하고 실패하면 예외를 던진다. 빈 생성 중에 연결하면 Discord 장애만으로 앱이 뜨지 않으므로,
    앱이 뜬 뒤(`ApplicationReadyEvent`) 별도 스레드에서 연결하고 실패하면 backoff(5초~5분)로 다시
    시도한다(`DiscordGateway`).
- 명령어는 `discord.guild-id` 서버에만 등록한다(서버 단위 등록은 즉시 반영된다). 비어 있으면
  global로 등록한다. 등록할 때 반대쪽 범위의 이전 등록은 지워서, guild-id를 바꿔도 명령어가 두 번
  보이지 않게 한다. 봇이 `applications.commands` 권한으로 초대돼 있어야 한다.
- `alert.mute.enabled`로 켜고 끈다. 기본은 꺼져 있고 prod 프로파일에서만 켠다. 로컬과 테스트는
  실제 Discord에 연결하지 않는다.

### 명령어 처리 순서

1. 입력한 채널이 알림 채널인지 확인한다. 아니면 ephemeral 오류.
2. **Discord에 먼저 응답(defer, ephemeral)한다.**
3. 응답이 성공한 경우에만 DB에 반영한다.
4. 채널에 공지 메시지를 보내고, ephemeral 응답을 `Done.`으로 바꾼다.

배포(RollingUpdate) 중에는 파드가 잠깐 2개가 되어 Gateway 연결도 2개가 되고, 같은 명령어를 둘 다
받을 수 있다. Discord는 interaction 하나에 한 번만 응답을 받으므로 2번을 먼저 하면 한쪽만 처리된다.

### 만료 처리

1분마다 만료된 mute를 찾는다. `UPDATE ... SET ended_at = now WHERE id = ? AND ended_at IS NULL`로
먼저 선점한 쪽만 만료 공지를 보낸다. `/unmute`도 같은 방식으로 선점한다. 파드가 2개일 때 두 번
알리지 않기 위해서다.

## 6. 범위 밖

- title 외(description, 로그 본문) 매칭, 정규식 매칭
- 알림 메시지에 붙는 mute 버튼
- Alertmanager Silence 연동

## 7. 검증

- `MuteService`: Testcontainers MySQL. 걸기, 연장, 해제, 만료 선점, 대소문자 무시 매칭, 채널 전체
  mute, 다른 채널에 영향 없음.
- 어댑터: mute된 알림은 보내지 않고, 다른 채널 알림은 그대로 보내고, DB 오류 시에도 보낸다.
- 명령어 처리: 옵션 해석, 알림 채널이 아닐 때 거절, 자동완성 목록.
- 배포 후: 메모리 사용량(한도 512Mi 유지)과 재시작 여부, Flyway V3 적용과 명령어 등록 로그,
  실제 채널에서 `/mute` → 알림 차단 → `/unmute` → 알림 복구.

## 8. 이전 설계에서 바뀐 점

| | 이전 (2026-09-08) | 지금 |
| --- | --- | --- |
| 범위 | Prometheus는 label matcher, Loki는 namespace + 로그 키워드 | 채널 단위, title 키워드 |
| Prometheus mute | Alertmanager Silence API 호출 | waffle-alert가 직접 판단 (모든 소스 공통) |
| Discord 연동 | HTTP Interactions Endpoint (외부 노출 필요) | Gateway (외부 노출 불필요) |
| 선행 인프라 작업 | VirtualService + TLS + 도메인 | 없음 |

- 메시지 포맷 정리로 title에 namespace와 리소스가 들어가게 되면서, title 하나로 모든 소스를 같은
  규칙으로 거를 수 있게 됐다.
- Loki 알림의 로그는 pod가 아니라 namespace 전체의 최근 5분을 조회하므로, 로그 줄 키워드로 알림
  전체를 억제하면 같은 시간대의 새 에러까지 가려진다. title 매칭은 이 문제가 없다.
- waffle-alert는 이미 K8s watch로 상시 연결을 유지하고 있어서 Gateway 연결을 추가하는 부담이 작다.
