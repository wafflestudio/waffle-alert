# Architecture — 핵심 개념과 흐름

> waffle-alert가 어떻게 동작하는지. 코드 짜기 전 개념 정리.

Monitoring provider 확장 경계와 Vault 설정 결정은
[Monitoring 확장 설계 결정](./monitoring-design-decisions.md)을 참고한다.

> 현재 구현 상태: `AlertIncident`/`AlertEventLog`(fingerprint 묶기, 상태전이, DB 저장)는 시도됐다가
> 미사용 상태로 제거됐다. `AlertIngestionService`는 DB를 거치지 않고 모든 AlertEvent를 받은 그대로
> Discord로 바로 전달한다. FIRING/RESOLVED 스팸 억제나 "같은 문제 몇 번 터짐" 같은 집계는 하지 않는다
> — 필요해지면 다시 설계한다.

## 1. 핵심 개념: AlertEvent

### AlertEvent — 순간 신호 1건

Alertmanager나 OCI가 보내는 1회성 메시지. "지금 이런 일이 일어났다"만 표현하고, 들어온 그대로
Discord로 나간다. 같은 문제가 반복돼도 묶지 않는다 — 신호가 오면 그때마다 알림도 간다.

```
10:00  "snutt MySQL CPU 85%" (FIRING)    → 즉시 Discord 알림
10:05  "snutt MySQL CPU 87%" (FIRING)    → 즉시 Discord 알림 (같은 문제라도 또 보냄)
10:10  "snutt MySQL CPU 90%" (FIRING)    → 즉시 Discord 알림
10:30  "snutt MySQL CPU 정상" (RESOLVED) → 즉시 Discord 알림
```

## 2. 전체 흐름

```
AlertEvent (순간 신호)
     │  들어옴
     ▼
AlertIngestionService.ingest(event)
     │
     └─ NotificationPort.notify(event) → Slack/Discord로 바로 전송
```

```kotlin
fun ingest(event: AlertEvent): Boolean = notificationPort.notify(event)
```

→ fingerprint 기준 상태 추적, 알림 스팸 억제, "이번 달 몇 번 터짐" 같은 통계는 현재 범위 밖이다.
필요해지면 이 지점(`ingest` 앞단)에 다시 넣는다.

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

### 시나리오 A — OCI Monitoring (MySQL CPU, 운영 15분 주기, 임계치 80%)

```
10:00  CPU 60%  → 정상. 아무 일도 안 함 (정상값은 AlertEvent를 만들지 않는다).
10:03  CPU 85%  → FIRING 판단! evaluator가 AlertEvent 생성 → 즉시 Discord 알림.
10:06  CPU 88%  → 여전히 FIRING. 매 polling마다 다시 AlertEvent 생성 → 또 알림 (묶지 않음).
10:09  CPU 70%  → 정상 복귀. AlertEvent를 만들지 않으므로 "해결됨" 알림도 없음.
```

→ OCI는 애초에 RESOLVED 개념이 없다 (정상값은 버려짐). 지속 장애는 polling 주기마다 계속
알림이 오고, 값이 정상으로 돌아가도 별도 "해결" 알림은 가지 않는다.
[monitoring-design-decisions.md](./monitoring-design-decisions.md)의 "현재 한계"를 참고.

### 시나리오 B — OCI Cost (일 1회, rule "오늘 비용 > 100 USD")

```
Day1  80 USD  → 정상.
Day2  130 USD → FIRING! Discord "비용 초과" ✅
Day3  140 USD → 또 초과. 다시 FIRING → 또 알림.
```

비용도 "순간 관측값이 threshold를 넘었는가"만 매번 독립적으로 판단한다. 이어지는 문제인지
새 문제인지 구분하는 로직(=Incident)이 없으므로 초과 상태가 계속되면 폴링마다 알림이 간다.

### 핵심: evaluator만 다르고 그 뒤는 공통

```
Prometheus     → [webhook 파싱] ───────────────────────────────────┐
Cloud resource → [polling → ResourceMetricObservation → evaluator] ─┼→ AlertEvent → AlertIngestionService (공통!)
OCI Cost       → [polling → 비용 전용 evaluator: 임계치 판정] ──────┘
```

→ ingestion/라우팅/알림 출구는 **셋 다 100% 공통.** Alertmanager는 이미 평가된 사건을 보내고,
cloud resource polling 경로만 `ResourceMetricObservation`과 evaluator를 거쳐 같은 `AlertEvent`로
합류한다.

## 4. 패키지 구조와 매핑

데이터가 왼쪽(입구) → domain(묶기) → 오른쪽(출구)로 흐른다 (hexagonal).

```
inbound/webhook       → AlertEvent 입구 (Alertmanager가 POST)
source/oci            → OCI Monitoring/Cost polling + scheduler
source/k8s            → 쿠버네티스 API watch (Pod/Job/Node)
source/loki           → 애플리케이션 에러 로그 원문 조회 (Discord 메시지 보강용)
       ↓
domain/model
  AlertEvent          → 순간 신호 (§1)
  ResourceMetricObservation → cloud resource polling의 판단 전 관측값
  Enums               → AlertSource / AlertStatus / Severity
domain/evaluator      → cloud resource metric/비용 평가 (Alertmanager는 불필요)
domain/service
  AlertIngestionService → NotificationPort.notify(event) 호출 (합류점, 상태 없음)
       ↓
outbound/notification → Slack/Discord 출구 + routing(namespace + alert 성격 → 팀 채널)
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
