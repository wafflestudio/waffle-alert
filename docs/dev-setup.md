# 개발 환경 / 배포 / 브랜치 전략

> 최종 업데이트: 2026-07-20

## 전체 DB 연결 환경  

| 환경 | DB | secret 주입 |
| --- | --- | --- |
| **로컬 Monitoring E2E** | 사용하지 않음 | Vault는 읽지만 DB auto-configuration을 실행 옵션으로 제외 |
| **테스트(CI)** | Testcontainers MySQL | 컨테이너가 임시 생성 + `@ServiceConnection` 자동 주입 |
| **운영(prod)** | MySQL | **OCI Vault** 주입 (`spring-boot-starter-waffle-oci-vault`) |

- 현재 AlertEvent 처리 경로는 DB에 저장하지 않고 Discord로 바로 전달한다.
- Incident/EventLog entity와 Flyway schema는 이후 fingerprint 묶기와 상태 저장을 위해 먼저 만들어졌다.
- Repository와 IncidentService는 아직 TODO지만 JPA/Flyway auto-configuration 때문에 기본 기동은 DB 연결을 요구한다.
- Vault의 DB는 private endpoint라 로컬 머신에서 route가 없어 connect timeout이 발생하고, OKE Pod에서는 VCN 내부 경로로 접근한다.

### 로컬 실행 (동작 확인됨)

Monitoring에서 Discord까지 확인할 때는 현재 사용하지 않는 DB 계층을 실행 옵션으로 제외한다.

```bash
./gradlew bootRun --args='--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration'
curl http://localhost:8080/actuator/health
```

DB를 포함한 전체 context를 로컬에서 실행하려면 private endpoint로 연결되는 VPN, bastion tunnel 또는
별도 local DB 설정이 필요하다.

스키마는 Flyway가 관리 (`src/main/resources/db/migration`).
DB 스키마 추가는 반드시 위 경로에 ddl 파일로 추가해줘야 flyway 충돌이 발생하지 않음

## Backlog : No dev DB

처음엔 여타 다른 서비스들 처럼 "dev DB를 로컬에서 붙어 테스트"를 고려했으나, waffle-alert 특성상 불필요하다고 결론.

- waffle-alert는 일반 서비스가 아니라 **클러스터 인프라 컴포넌트** (truffle / k8s-monitoring과 같은 성격). 모니터링 대상이 **클러스터 하나**라 dev/prod 데이터를 나눌 이유가 약하다.
- **OCI Cost/Monitoring은 공용 API**라 로컬에서 `~/.oci/config`만 있으면 직접 호출된다 (클러스터 무관). 즉 OCI 연동 개발에 공용 dev DB가 필요 없다.
- 공용 dev DB가 정말 필요해지는 시점(데이터 공유 / 24h 축적 / 배포 리허설)이 오면 그때 추가한다.

→ **결론: local 프로파일에서 OCI CLI 인증으로 Vault와 Monitoring API를 직접 호출하고, 현재 사용하지
않는 persistence auto-configuration은 Monitoring E2E에서만 제외한다.**

### 로컬 OCI Monitoring -> Discord 확인

로컬 OCI 조회와 Vault 조회는 `~/.oci/config`의 OCI CLI profile을 사용한다. 기본 `local` 프로파일은
`oci.auth.type=config`으로 동작하고, 운영 `prod` 프로파일은 Instance Principal을 사용한다.

기본 `local` 프로파일은 Discord 전달 확인을 위해 threshold만 낮게 덮어쓴다.
polling 주기는 local 1분, prod 3분이다. prod는 4분 window로 조회해 실행 사이에 1분을 겹친다.
DB System OCID와 compartment OCID를 포함한 공통 설정은 Vault에서 읽으므로 별도 export가 필요 없다.
Vault secret의 `spring.datasource.*`는 모니터링 대상 DB가 아니라 `waffle-alert` 자체 DB를 가리켜야 한다.
Docker MySQL은 사용하지 않는다.

```bash
# ~/.oci/config의 DEFAULT가 아닌 profile을 사용할 때만 지정한다.
export OCI_CONFIG_PROFILE='PROFILE_NAME'
./gradlew bootRun --args='--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration'
```

Vault JSON에는 최소한 다음 값이 있어야 한다.

```text
spring.datasource.url
spring.datasource.username
spring.datasource.password
discord.bot-token
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.id
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.compartment-id
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.service
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.team
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.enabled
```

`db-systems`는 이름을 key로 쓰는 map이다. 같은 `wafflestudio-mysql` key 아래 Vault의 공통값과
프로파일 YAML의 threshold가 병합된다. `local`은 Discord 전달 확인을 위한 낮은 값을 사용하고,
`prod`는 운영값을 사용한다.

조회 실패는 애플리케이션 로그의 `Failed to poll OCI MySQL metric`에서 확인하고,
성공한 threshold 초과 event는 `oci-monitoring` Discord channel로 확인한다.
2026-07-20 local E2E에서 CPU와 DB volume FIRING 메시지의 Discord 수신을 확인했다.

### 배포 파이프라인 (구축됨)

```
main push
  → CI (ci.yml): ktlint + Testcontainers 테스트
  → deploy-prod.yml: Docker 이미지 빌드 → OCIR push → ArgoCD 배포
```

- `Dockerfile`: 멀티스테이지 (temurin 25 jdk 빌드 → jre 런타임). 검증 완료.
- 와플 공통모듈(spring-waffle)은 GitHub Packages에서 받으며 빌드 시 `GITHUB_TOKEN`(`read:packages` 스코프) 필요.
- 운영 manifest는 `SPRING_PROFILES_ACTIVE=prod`와 `waffle-alert` ServiceAccount를 사용한다.
- main merge 후 새 이미지가 배포되면 첫 polling은 앱 기동 후 시작하고, 이후 각 실행 완료 시점부터 3분 간격으로 반복한다.
- Pod의 Instance Principal에 Vault secret과 Monitoring metric read 권한이 있어야 하며, DB startup 연결도 성공해야 한다.
- 운영 metric이 warning threshold보다 낮으면 Discord 메시지가 없는 것이 정상이다. 배포 성공과 polling 실패 여부는 Pod 로그로 확인한다.

## 브랜치 전략

```
feat/*, fix/*  ──PR──> main ──> prod 배포
```

- 기능/수정은 `feat/*`, `fix/*` → `main`으로 PR
- `main` 머지 시 CI + 이미지 빌드 자동 실행
- develop 없이 단순하게 (인프라 컴포넌트라 환경 분리 최소)
- 커밋 메시지 한글 자유 (예: `feat: webhook 수신 컨트롤러 추가`)
