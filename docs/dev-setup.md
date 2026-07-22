# 개발 환경 / 배포 / 브랜치 전략

> 최종 업데이트: 2026-07-20

## 전체 DB 연결 환경  

| 환경 | DB | secret 주입 |
| --- | --- | --- |
| **로컬 Monitoring E2E** | Docker MySQL | 환경변수로 Discord와 Monitoring 대상 설정 주입 |
| **테스트(CI)** | Testcontainers MySQL | 컨테이너가 임시 생성 + `@ServiceConnection` 자동 주입 |
| **운영(prod)** | MySQL | **OCI Vault** 주입 (`spring-boot-starter-waffle-oci-vault`) |

- 현재 AlertEvent 처리 경로는 DB에 저장하지 않고 Discord로 바로 전달한다.
- Incident/EventLog entity와 Flyway schema는 이후 fingerprint 묶기와 상태 저장을 위해 먼저 만들어졌다.
- Repository와 IncidentService는 아직 TODO지만 JPA/Flyway auto-configuration 때문에 기본 기동은 DB 연결을 요구한다.
- 운영 DB와 Vault는 로컬에서 접근하지 않는다.

### 로컬 실행 (동작 확인됨)

기존 로컬 실행과 동일하게 Docker MySQL을 먼저 실행한다.

```bash
docker compose up -d
./gradlew bootRun
curl http://localhost:8080/actuator/health
```

스키마는 Flyway가 관리 (`src/main/resources/db/migration`).
DB 스키마 추가는 반드시 위 경로에 ddl 파일로 추가해줘야 flyway 충돌이 발생하지 않음

## Backlog : No dev DB

처음엔 여타 다른 서비스들 처럼 "dev DB를 로컬에서 붙어 테스트"를 고려했으나, waffle-alert 특성상 불필요하다고 결론.

- waffle-alert는 일반 서비스가 아니라 **클러스터 인프라 컴포넌트** (truffle / k8s-monitoring과 같은 성격). 모니터링 대상이 **클러스터 하나**라 dev/prod 데이터를 나눌 이유가 약하다.
- **OCI Cost/Monitoring은 공용 API**라 로컬에서 `~/.oci/config`만 있으면 직접 호출된다 (클러스터 무관). 즉 OCI 연동 개발에 공용 dev DB가 필요 없다.
- 공용 dev DB가 정말 필요해지는 시점(데이터 공유 / 24h 축적 / 배포 리허설)이 오면 그때 추가한다.

→ **결론: local 프로파일은 Docker MySQL과 직접 주입한 환경변수를 사용하고, OCI Monitoring API는
OCI CLI 인증으로 호출한다. Vault는 prod에서만 사용한다.**

### 로컬 OCI Monitoring -> Discord 확인

로컬 OCI 조회는 `~/.oci/config`의 OCI CLI profile을 사용한다. 기본 `local` 프로파일은
`oci.auth.type=config`으로 동작하고, 운영 `prod` 프로파일은 Instance Principal과 Vault를 사용한다.

기본 `local` 프로파일은 Discord 전달 확인을 위해 threshold만 낮게 덮어쓴다.
polling 주기는 local 1분, prod 3분이다. prod는 4분 window로 조회해 실행 사이에 1분을 겹친다.
DB System OCID와 compartment OCID, 대상 활성화 여부, Discord bot token은 커밋하지 않고
환경변수로 주입한다.

```bash
# ~/.oci/config의 DEFAULT가 아닌 profile을 사용할 때만 지정한다.
export OCI_CONFIG_PROFILE='PROFILE_NAME'
export DISCORD_BOT_TOKEN='...'
export OCI_MONITORING_DB_SYSTEM_ID='ocid1.mysqldbsystem...'
export OCI_MONITORING_COMPARTMENT_ID='ocid1.compartment...'
export OCI_MONITORING_DB_SYSTEM_ENABLED='true'
docker compose up -d
./gradlew bootRun
```

`local`은 Discord 전달 확인을 위한 낮은 threshold를 사용하고, `prod`는 운영값을 사용한다.

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
