# Architecture — 핵심 개념과 흐름

> waffle-alert가 어떻게 동작하는지. 코드 짜기 전 개념 정리.

Monitoring provider 확장 경계와 Vault 설정 결정은
[Monitoring 확장 설계 결정](./monitoring-design-decisions.md)을 참고한다.

> 현재 구현 상태: Incident/EventLog entity와 schema만 준비돼 있고 repository와 IncidentService는 TODO다.
> `AlertIngestionService`는 DB를 거치지 않고 모든 AlertEvent를 Discord로 바로 전달한다.

## 1. 핵심 개념: AlertEvent / Incident / EventLog

병원 응급실 비유:

```
환자가 "배 아파요" 하고 옴      = AlertEvent  (그 순간의 신고 1건)
그 환자의 진료 차트            = Incident    (한 명의 환자 = 하나의 문제)
차트 안의 진료 타임라인        = EventLog    (10:00 입원, 11:00 검사, 14:00 퇴원)
```

핵심: **같은 환자가 5번 "배 아파요" 해도 환자(차트)는 1명.** 신고는 여러 번이지만 문제는 1개.

### AlertEvent — 순간 신호 1건

Alertmanager나 OCI가 보내는 1회성 메시지. 상태 없음. "지금 이런 일이 일어났다".

```
10:00  "snutt MySQL CPU 85%" (FIRING)   ← AlertEvent 1
10:05  "snutt MySQL CPU 87%" (FIRING)   ← AlertEvent 2 (같은 문제 또)
10:10  "snutt MySQL CPU 90%" (FIRING)   ← AlertEvent 3 (또)
10:30  "snutt MySQL CPU 정상" (RESOLVED) ← AlertEvent 4
```

→ 문제가 지속되면 같은 신호가 반복해서 들어온다.

### Incident — 같은 문제 하나 (★ 가장 중요)

위 4개 AlertEvent는 사실 **같은 문제 하나**. 이걸 묶은 게 Incident.

```
Incident #1
  fingerprint:  "snutt-prod / mysql-cpu-high"   ← 같은 문제인지 식별하는 지문
  status:       FIRING → (10:30) RESOLVED
  started_at:   10:00
  last_seen_at: 10:10
  resolved_at:  10:30
  notify_count: 1   ← 슬랙엔 1번만 (4번 다 보내면 스팸)
```

**Incident가 없으면:** 슬랙 스팸, 진행/해결 여부 모름, 과거 추적 불가.
**Incident가 있으면:** 같은 fingerprint는 묶고 알림 1번, status로 열림/닫힘 관리, "이번 달 5번 터짐" 통계.

→ **fingerprint가 핵심.** "이미 있는 문제인가 새 문제인가"를 판단하는 지문. 보통 `service + metric + resource` 조합. 같은 지문 = 같은 incident.

### EventLog — Incident의 타임라인

Incident 하나의 히스토리. 위 4개 AlertEvent가 이렇게 기록됨:

```
Incident #1 의 EventLog:
  10:00  FIRING
  10:00  NOTIFICATION_SENT
  10:05  REPEATED            ← 또 왔지만 같은 문제 (슬랙 안 보냄)
  10:10  REPEATED
  10:30  RESOLVED
  10:30  NOTIFICATION_SENT   ← "해결됨" 알림
```

→ **1 Incident : N EventLog** (1:N). DB도 테이블 2개(`alert_incidents`, `alert_event_logs`)를 FK로 연결.

## 2. 전체 흐름

```
AlertEvent (순간 신호, 여러 개)
     │  들어옴
     ▼
AlertIngestionService  ← "새 문제? 기존 문제?"
     │
     ├─ fingerprint로 검색
     │    ├─ 기존 Incident 있음 → 묶음 (REPEATED / RESOLVED 갱신)
     │    └─ 없음 → 새 Incident 생성 (FIRING)
     │
     ├─ EventLog에 한 줄 기록 (타임라인)
     │
     └─ 필요하면 Slack/Discord 알림
```

가장 중요한 로직 (`IncidentService`):

```kotlin
fun ingest(event: AlertEvent) {
    val existing = incidentRepo.findByFingerprint(event.fingerprint)
    if (existing == null) {
        // 새 문제 → 생성 + 알림
        val incident = Incident(status = FIRING, ...)
        eventLog.record(incident, FIRING); notify(incident)
    } else if (event.status == RESOLVED) {
        // 해결 → 닫기 + "해결" 알림
        existing.resolve(); eventLog.record(existing, RESOLVED); notify(existing)
    } else {
        // 같은 문제 또 옴 → 묶기만 (알림 스킵 or N번마다)
        existing.touch(); eventLog.record(existing, REPEATED)
    }
}
```

→ 이 if/else가 "스팸 안 되게 + 상태 추적되게" 만드는 심장. 나머지(webhook 파싱, OCI 호출, 슬랙 전송)는 전부 이걸 위한 주변부.

## 3. 입력 2경로: Prometheus vs OCI

근본 차이 — **Prometheus는 상태를 주고, OCI는 안 준다.**

```
Prometheus/Alertmanager:
  "FIRING이야", "RESOLVED야"  ← 상태 만들어서 push. 너는 받아서 묶기만.

OCI Monitoring/Cost API:
  "지금 CPU 85%야"  ← 숫자만 pull. FIRING인지 모름.
  너의 evaluator가 "85% > 80% → FIRING" 판단.
  다음에 또 긁어 "60%네 → RESOLVED" 도 너가 판단.
```

| | Prometheus/Alertmanager | OCI Monitoring | OCI Cost |
| --- | --- | --- | --- |
| 데이터 | 상태 포함 | 숫자만 (pull) | 숫자만 (pull, 일단위) |
| FIRING 판단 | Alertmanager | **evaluator** | **evaluator** |
| RESOLVED 판단 | push로 받음 | **"정상값 보면 닫기"** | 보통 안 닫음 (일단위 사건) |
| fingerprint | payload/라벨 | service+metric+resource | metric + **날짜**(보통) |
| evaluator | 불필요 | 필요 | 필요 |

### 시나리오 A — OCI Monitoring (MySQL CPU, 운영 3분 주기, 임계치 80%)

```
10:00  CPU 60%  → 정상. incident 없음.
10:03  CPU 85%  → FIRING 판단! fingerprint "snutt-mysql/cpu-high" 없음 → Incident #1 생성, 슬랙 ✅
10:06  CPU 88%  → 여전히 FIRING. 같은 fingerprint → Incident #1에 묶음, REPEATED, 슬랙 X
10:09  CPU 70%  → 정상 복귀! 열린 Incident #1 있음 → RESOLVED 처리, "해결" 슬랙 ✅
```

→ **차이: OCI는 RESOLVED를 안 보내준다.** evaluator가 "이번 측정이 정상인데 열린 incident가 있으면 → 닫는다"를 직접 판단해야 함.

### 시나리오 B — OCI Cost (일 1회, rule "오늘 비용 > 100 USD")

```
Day1  80 USD  → 정상.
Day2  130 USD → FIRING! 슬랙 "비용 초과" ✅
Day3  140 USD → 또 초과. fingerprint 설계에 따라 갈림 ↓
```

비용은 "순간 사건"이 아니라 "일단위 추세"라 fingerprint 설계 결정 필요:

```
방식 A) fingerprint = "daily-cost-exceed"          → Day2~3 한 incident (이어지는 문제). 매일 알림 X.
방식 B) fingerprint = "daily-cost-exceed/2026-06-24" → 매일 다른 incident. 매일 알림.
```

→ 비용은 보통 **방식 B(날짜 포함)** 가 자연스러움.

### 핵심: evaluator만 다르고 그 뒤는 공통

```
Prometheus    → [webhook 파싱] ───────────────────────┐
OCI Monitoring → [긁기 → evaluator: 임계치+상태판단] ──┼→ AlertEvent → AlertIngestionService (공통!)
OCI Cost      → [긁기 → evaluator: 임계치+날짜fp] ─────┘
```

→ Incident/EventLog 구조와 저장/라우팅/알림은 **셋 다 100% 공통.** OCI만 AlertEvent를 만들기까지(evaluator) 일을 더 할 뿐. 그래서 evaluator가 OCI 쪽에만 있다.

### OCI 때문에 추가로 구현할 것

1. **RESOLVED 자동 판단** (Monitoring): "열린 incident가 있는데 이번 측정이 정상이면 닫기" → IncidentService에 케이스 추가.
2. **fingerprint에 날짜** (Cost): 일단위 사건은 날짜 포함이 자연스러움 → evaluator가 결정.

## 4. 패키지 구조와 매핑

데이터가 왼쪽(입구) → domain(묶기) → 오른쪽(출구)로 흐른다 (hexagonal).

```
inbound/webhook       → AlertEvent 입구 (Alertmanager가 POST)
source/oci            → AlertEvent 입구 (OCI 긁어서) + scheduler + evaluator
       ↓
domain/model
  AlertEvent          → 순간 신호 (§1)
  AlertIncident       → 묶인 문제 (§1) = @Entity = alert_incidents
  AlertEventLog       → 타임라인 (§1) = @Entity = alert_event_logs
  Enums               → AlertSource / AlertStatus / Severity
domain/evaluator      → OCI 임계치+상태 판단 (Prometheus는 불필요)
domain/service
  IncidentService     → fingerprint 묶기 / 상태전이 (★ 심장)
  AlertIngestionService → 받아서→묶고→기록→알림 조율 (두 경로 합류점)
       ↓
outbound/notification → Slack/Discord 출구 + routing(namespace→팀 매핑)
persistence           → Incident/EventLog 저장·조회 (JpaRepository)
config                → scheduling, HTTP client
```

## 5. 로컬에서 각 source 테스트하는 법

source가 "OCI 공용 API냐 / 클러스터 내부냐"로 갈린다.

| Source | 로컬 접근 | 방법 |
| --- | --- | --- |
| OCI Cost/Monitoring | ✅ 직접 | `~/.oci/config` 인증 → 공용 API 호출 |
| Alertmanager webhook | ❌ 직접 X | `curl`로 샘플 payload를 로컬 서버에 발사 |
| Prometheus query | ❌ 직접 X | `kubectl port-forward svc/prometheus 9090` |

```bash
# webhook 로직 테스트: 샘플 payload 직접 쏘기
curl -X POST http://localhost:8080/api/v1/alerts/webhook \
  -H "Content-Type: application/json" -d @sample-alertmanager-payload.json
```

→ OCI는 로컬에서 실데이터로 개발 가능. Prometheus/Alertmanager는 모킹/포트포워딩으로 우회.

## 6. 테스트 전략 (snutt 패턴)

테스트는 **Testcontainers + `@ServiceConnection`** 으로 진짜 MySQL 위에서 돈다 (H2 mock 아님).

```kotlin
// MySQLTestContainerConfig.kt
@TestConfiguration
class MySQLTestContainerConfig {
    @Bean
    @ServiceConnection
    fun mysqlContainer() = MySQLContainer(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("waffle_alert")
}

// 테스트
@SpringBootTest
@Import(MySQLTestContainerConfig::class)
class WaffleAlertApplicationTests { @Test fun contextLoads() {} }
```

- 테스트 `application.yml`엔 datasource 접속정보를 두지 않는다. `@ServiceConnection`이 컨테이너 url/user/password를 자동 주입.
- 장점: 진짜 MySQL이라 Flyway DDL의 `JSON` 타입 등이 H2와 달리 정확히 검증됨. 개발자가 DB 미리 안 띄워도 됨.
- CI(GitHub Actions)는 Docker를 기본 지원하므로 그대로 통과.

## 7. 배포 / 환경 결정 (확정)

waffle-alert는 일반 서비스가 아니라 **클러스터 인프라 컴포넌트** (truffle / k8s-monitoring과 같은 성격).

- 모니터링 대상이 **클러스터 하나**라, dev/prod 인스턴스를 나눌 이유가 약함 → **prod Pod 1개로 시작** (truffle도 prod만 실재).
- **dev Pod 불필요**: Monitoring local E2E는 Docker MySQL과 환경변수를 사용해 OCI API와 Discord를 확인한다. 공용 dev DB는 MVP에 두지 않는다.
- 환경 분리가 의미 있는 건 **알림 채널**뿐 (개발 #alert-test / 운영 #infra-critical) — truffle 방식.

### 브랜치 전략

```
feat/xxx, fix/xxx ──PR──> main ──> prod 배포
```

develop 없이 단순하게. 커밋 메시지 한글 자유 (예: `feat: webhook 수신 컨트롤러 추가`).

### 배포 파이프라인

```
main push
  → CI (ci.yml): ktlint + Testcontainers 테스트
  → deploy-prod.yml: Docker 이미지 빌드 → OCIR push
  → ocir-image-updater(별도 레포): waffle-world-oci 이미지 태그 자동 갱신
  → ArgoCD: 갱신 감지 → Pod 재배포
```

- `Dockerfile`: 멀티스테이지 (temurin 25 jdk 빌드 → jre 런타임).
- 와플 공통모듈(spring-waffle)은 GitHub Packages에서 받음. 빌드 시 `GITHUB_TOKEN`(`read:packages`) 필요. Dockerfile은 `--secret`으로 주입(이미지에 안 남음).
- 이미지 태그 갱신/ArgoCD 배포는 우리가 안 건드림 — ocir-image-updater가 처리.

### prod 인증 / secret (Vault + Instance Principal)

```
DB 접속정보   → OCI Vault secret (JSON: spring.datasource.url/username/password)
              → oci-vault starter가 secret-ids(OCID)에서 주입
OCI 인증      → Instance Principal (Pod 신원 기반, 키 파일 불필요)
              → starter가 자동 처리 (auth.type 명시 안 해도 auto 폴백)
```

→ 따라서 Pod에 넣는 키는 없고, **IAM Policy만** 정의하면 된다 (Dynamic Group 기준):

```
Allow dynamic-group <waffle-alert-dg> to read secret-family in compartment <X>   # Vault 읽기
Allow dynamic-group <waffle-alert-dg> to read metrics in compartment <X>         # OCI Monitoring
Allow dynamic-group <waffle-alert-dg> to read usage-reports in tenancy           # OCI Cost
```

| 항목 | 방식 |
| --- | --- |
| DB url/user/password | Vault secret (JSON) |
| Slack/Discord webhook·token | Vault secret (알림 구현 시 추가) |
| OCI Monitoring/Cost 인증 | Instance Principal + IAM Policy (secret 아님) |
| Vault 읽기 권한 | IAM Policy |
