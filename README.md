# waffle-alert

WaffleStudio 인프라/비용/리소스 alert 플랫폼. Truffle(exception observability)과 나란히 서는 generic alert service로,
Prometheus/Alertmanager·OCI Cost·OCI Monitoring 등 여러 source의 alert를 공통 모델로 정규화해 저장·라우팅한다.

## 로컬 실행

```bash
docker compose up -d        # 로컬 MySQL 기동 (포트 3306)
./gradlew bootRun           # 또는 IntelliJ에서 WaffleAlertApplication 실행

curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

스키마는 Flyway가 관리한다 (`src/main/resources/db/migration`).

## 아키텍처 초안

### 제안 아키텍처

```
                    +-------------------+
Prometheus -------->|                   |
Alertmanager ----->|                   |
OCI Cost API ----->|   Source Adapter   |
OCI Monitoring --->|                   |
MySQL Exporter/API->|                   |
                    +---------+---------+
                              |
                              v
                    +-------------------+
                    |     Evaluator     |
                    |                   |
                    | - rule evaluate   |
                    | - normalize       |
                    | - fingerprint     |
                    +---------+---------+
                              |
                              v
                    +-------------------+
                    |      Storage      |
                    |                   |
                    | - alert_rules     |
                    | - incidents       |
                    | - event_logs      |
                    +---------+---------+
                              |
                              v
                    +-------------------+
                    |   Notification    |
                    |                   |
                    | - Slack           |
                    | - Discord         |
                    | - Webhook         |
                    +-------------------+
```

> 입력은 두 경로로 수렴한다. Prometheus 계열은 **Alertmanager webhook**으로 들어오고(별도 adapter 불필요),
> OCI(Cost/Monitoring)만 Prometheus 밖이라 **scheduler가 SDK로 직접 조회**한다.

## Spring Boot 아키텍처 구조

도메인은 hexagonal(port-adapter) 성격을 살리되, MVP라 단일 모듈로 시작한다.

```
com.wafflestudio.alert
├── WaffleAlertApplication.kt         # @SpringBootApplication, @EnableScheduling
│
├── inbound                           # 들어오는 alert
│   └── webhook
│       ├── AlertmanagerWebhookController.kt   # POST /api/v1/alerts/webhook
│       └── dto/AlertmanagerPayload.kt         # Alertmanager webhook payload
│
├── source                            # OCI 직접 조회 (Prometheus 밖 source)
│   ├── oci
│   │   ├── OciMonitoringAdapter.kt   # MySQL 인스턴스 CPU/mem/storage
│   │   ├── OciCostAdapter.kt         # Cost/Usage API
│   │   └── OciClientConfig.kt        # OCI SDK 인증 (config / instance principal)
│   └── scheduler
│       ├── OciMonitoringScheduler.kt # 주기 조회 -> evaluator
│       └── OciCostScheduler.kt       # daily/hourly 비용 집계 -> evaluator
│
├── domain                            # 핵심 모델/로직 (프레임워크 독립)
│   ├── model
│   │   ├── AlertEvent.kt             # 공통 정규화 모델
│   │   ├── ResourceMetricObservation.kt # cloud resource polling 중간 모델
│   │   ├── AlertIncident.kt          # @Entity
│   │   ├── AlertEventLog.kt          # @Entity
│   │   └── Enums.kt                  # AlertSource, AlertStatus, Severity
│   ├── evaluator
│   │   ├── OciCostEvaluator.kt       # threshold / 증가율 판단
│   │   └── OciResourceEvaluator.kt   # CPU/mem/storage 판단
│   └── service
│       ├── IncidentService.kt        # fingerprint upsert, 상태전이
│       └── AlertIngestionService.kt  # AlertEvent -> incident/log -> notify
│
├── outbound                          # 나가는 알림
│   └── notification
│       ├── NotificationPort.kt       # 인터페이스
│       ├── SlackNotificationAdapter.kt
│       ├── DiscordNotificationAdapter.kt
│       ├── WebhookNotificationAdapter.kt
│       └── routing
│           ├── RoutingPolicy.kt      # namespace/service -> 채널/팀 매핑
│           └── TeamMappingConfig.kt  # @ConfigurationProperties (yaml로 관리)
│
├── persistence
│   ├── AlertIncidentRepository.kt    # JpaRepository
│   └── AlertEventLogRepository.kt
│
└── config
    ├── SchedulingConfig.kt
    └── WebClientConfig.kt            # Slack/Discord 전송용 HTTP client
```

### 요청 흐름

```
[경로 ①] Alertmanager webhook
  AlertmanagerWebhookController
    -> AlertmanagerPayload 파싱
    -> AlertEvent 정규화
    -> AlertIngestionService
        -> IncidentService.upsert(fingerprint)   # 상태전이/repeat 판단
        -> AlertEventLog 기록
        -> RoutingPolicy로 채널/팀 결정
        -> NotificationPort 전송 (Slack @팀태깅 / Discord)

[경로 ②] OCI scheduler
  OciMonitoringScheduler / OciCostScheduler (@Scheduled)
    -> OciMonitoringAdapter / OciCostAdapter (SDK 조회)
    -> Monitoring: ResourceMetricObservation -> Evaluator (threshold 판단)
    -> Cost: 비용 전용 evaluator
    -> AlertEvent 정규화
    -> AlertIngestionService  (이하 ①과 동일 합류)
```

핵심: 두 경로가 `AlertEvent` -> `AlertIngestionService`에서 **하나로 합류**한다. 저장/라우팅/알림 로직은 source와 무관하게 공통이다.

## Tech Spec

| 항목 | 선택 | 비고 |
| --- | --- | --- |
| Language | Kotlin 2.3.0 | 와플 표준 |
| JDK | 25 (LTS) | |
| Framework | Spring Boot 4.0.7 | JDK 25 지원. 와플 공통모듈 검증된 4.0 라인 |
| Web | Spring MVC (`spring-boot-starter-web`) | 동기/blocking, REST API. WebFlux 불필요 |
| 영속성 | Spring Data JPA (Hibernate) | incident/eventlog가 관계형·상태기반 |
| DB | MySQL (운영/로컬), H2 / Testcontainers (테스트) | 스키마는 Flyway 관리 |
| 동적쿼리 | 없음 (MVP) | 필요시 Kotlin JDSL (kapt 없이 순수 DSL) |
| 스케줄링 | `@Scheduled` (Spring) | OCI 주기 조회용 |
| HTTP client | Spring `RestClient` | Slack/Discord 전송 |
| 재시도/제한 | resilience4j (spring-cloud-circuitbreaker) | notification 재시도 |
| 직렬화 | jackson-module-kotlin | |
| 빌드 | Gradle Kotlin DSL | |
| 테스트 | JUnit5 + MockK + Testcontainers | Kotlin이라 Mockito 대신 MockK |
| 배포 | Docker + ArgoCD GitOps | native image는 MVP 이후 |
| 시크릿 | OCI Vault (`spring-boot-starter-waffle-oci-vault`) | prod. local은 직접 설정 |

> prod의 DB 등 secret은 OCI Vault에서 주입한다 (snutt 패턴). `application-prod.yml`은
> 골격만 두고, `spring-boot-starter-waffle-oci-vault` 추가 + secret OCID 연결은 배포 단계에서 한다.
> 와플 공통 모듈은 GitHub Packages(`maven.pkg.github.com/wafflestudio/spring-waffle`)에서 받으며
> 빌드 시 `GITHUB_TOKEN` 또는 `gh auth token`이 필요하다.
