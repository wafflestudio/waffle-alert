# 개발 환경 / 배포 / 브랜치 전략

> 최종 업데이트: 2026-06-25

## 전체 DB 연결 환경  

| 환경 | DB | secret 주입 |
| --- | --- | --- |
| **로컬 개발** | Docker MySQL (`docker-compose.yml`, 3306) | `application.yml` local 프로파일에 더미값 (로컬 전용, 노출 무방) |
| **테스트(CI)** | Testcontainers MySQL | 컨테이너가 임시 생성 + `@ServiceConnection` 자동 주입 |
| **운영(prod)** | MySQL | **OCI Vault** 주입 (`spring-boot-starter-waffle-oci-vault`) |

- 로컬 개발시, local Mysql 연결로 서버 실행 및 DB 연결 테스트 가능
- dev DB는 로그 수집 및 alert 서비스 특성 상 분리하지 않고, local - prod 환경으로만 설정함

### 로컬 실행 (동작 확인됨)

```bash
docker compose up -d        # 로컬 MySQL 기동 (3306)
./gradlew bootRun           # 기본 프로파일 = local
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

스키마는 Flyway가 관리 (`src/main/resources/db/migration`).
DB 스키마 추가는 반드시 위 경로에 ddl 파일로 추가해줘야 flyway 충돌이 발생하지 않음

## Backlog : No dev DB

처음엔 여타 다른 서비스들 처럼 "dev DB를 로컬에서 붙어 테스트"를 고려했으나, waffle-alert 특성상 불필요하다고 결론.

- waffle-alert는 일반 서비스가 아니라 **클러스터 인프라 컴포넌트** (truffle / k8s-monitoring과 같은 성격). 모니터링 대상이 **클러스터 하나**라 dev/prod 데이터를 나눌 이유가 약하다.
- **OCI Cost/Monitoring은 공용 API**라 로컬에서 `~/.oci/config`만 있으면 직접 호출된다 (클러스터 무관). 즉 OCI 연동 개발에 공용 dev DB가 필요 없다.
- 공용 dev DB가 정말 필요해지는 시점(데이터 공유 / 24h 축적 / 배포 리허설)이 오면 그때 추가한다.

→ **결론: local Docker DB + OCI 직접 호출로 시작. 공용 dev DB·dev Pod 없음.**

### 로컬 OCI Monitoring -> Discord 확인

로컬 OCI 조회는 `~/.oci/config`의 OCI CLI profile을 사용한다. 기본 `local` 프로파일은
`oci.auth.type=config`으로 동작하고, 운영 `prod` 프로파일은 Instance Principal을 사용한다.

`oci-test` 프로파일은 Discord 전달 확인을 위해서만 사용하며 threshold가 낮게 설정되어 있다.
DB System OCID와 compartment OCID는 환경변수로만 넘긴다.

```bash
docker compose up -d
export DISCORD_BOT_TOKEN='...'
export OCI_MYSQL_DB_SYSTEM_ID='ocid1.mysqldbsystem...'
export OCI_MYSQL_COMPARTMENT_ID='ocid1.compartment...'
./gradlew bootRun --args='--spring.profiles.active=local,oci-test'
```

조회 실패는 애플리케이션 로그의 `Failed to poll OCI MySQL metric`에서 확인하고,
성공한 threshold 초과 event는 `oci-monitoring` Discord channel로 확인한다.

### 배포 파이프라인 (구축됨)

```
main push
  → CI (ci.yml): ktlint + Testcontainers 테스트
  → deploy-prod.yml: Docker 이미지 빌드 → OCIR push → ArgoCD 배포
```

- `Dockerfile`: 멀티스테이지 (temurin 25 jdk 빌드 → jre 런타임). 검증 완료.
- 와플 공통모듈(spring-waffle)은 GitHub Packages에서 받으며 빌드 시 `GITHUB_TOKEN`(`read:packages` 스코프) 필요.

## 브랜치 전략

```
feat/*, fix/*  ──PR──> main ──> prod 배포
```

- 기능/수정은 `feat/*`, `fix/*` → `main`으로 PR
- `main` 머지 시 CI + 이미지 빌드 자동 실행
- develop 없이 단순하게 (인프라 컴포넌트라 환경 분리 최소)
- 커밋 메시지 한글 자유 (예: `feat: webhook 수신 컨트롤러 추가`)
