# Monitoring 확장 설계 결정

> 최종 업데이트: 2026-07-20

이 문서는 OCI Monitoring을 구현하면서 결정한 hexagonal 경계와 설정 관리 방식을 정리한다.
현재 구현을 설명하는 동시에 AWS CloudWatch, Prometheus 등 다른 provider를 추가할 때의 기준으로 사용한다.

## 1. 목표

- OCI SDK 응답이 알림 도메인 전체로 퍼지지 않게 한다.
- provider가 달라도 같은 형태의 metric 관측값과 알림 사건을 사용한다.
- 인증, API query, dimension 해석처럼 provider마다 다른 부분은 source 계층에 둔다.
- threshold 판정과 `AlertEvent` 생성은 공통 evaluator에서 처리한다.
- 운영 설정은 Vault와 profile YAML의 책임을 구분하고 코드 리뷰로 비교할 수 있게 한다.

이번 MVP는 `FIRING` 생성과 Discord 전달까지만 다룬다. 정상 범위 복귀에 따른 `RESOLVED`, 반복
event와 Incident 처리는 이후 단계다. Incident용 entity와 schema는 존재하지만 현재 ingestion 경로는
DB를 사용하지 않는다.

## 2. 용어 구분

### Provider

`MetricObservation.provider`는 원본 metric을 제공한 시스템이다.

```text
OCI
AWS_CLOUDWATCH
PROMETHEUS
```

provider는 evaluator가 어떤 rule을 적용할지 판단하는 입력이다. OCI와 AWS가 모두 CPU 사용률을
제공하더라도 resource type, 단위 또는 운영 의미가 다르면 서로 다른 rule을 선택할 수 있다.

### Alert source

`AlertEvent.source`는 완성된 사건이 어떤 알림 경로에서 만들어졌는지를 나타낸다.

```text
ALERTMANAGER
OCI_MONITORING
OCI_COST
```

즉 `provider`는 관측 데이터의 출처이고, `source`는 알림 사건의 분류다. evaluator는 observation의
provider와 rule을 통해 출력 `AlertEvent.source`를 결정한다. 둘을 하나의 필드로 합치지 않는다.

### Observation과 AlertEvent

- `MetricObservation`: 판단 전의 provider 중립적인 관측값
- `AlertEvent`: rule 평가가 끝난 뒤 알림, 저장, Incident 처리에 사용하는 공통 사건

Observation은 "CPU가 85%였다"만 표현한다. AlertEvent는 "warning threshold를 넘어서 FIRING
사건이 발생했다"를 표현한다.

## 3. 현재 처리 흐름

```text
OCI scheduler
  -> OCI monitoring adapter
  -> OCI Monitoring API
  -> MetricObservation
  -> ResourceMetricEvaluator
       -> provider + resourceType + metricKind + unit으로 rule 선택
       -> threshold 판정
  -> AlertEvent
  -> AlertIngestionService
  -> NotificationPort
  -> Discord
```

각 계층의 책임은 다음과 같다.

| 구성요소 | 책임 | provider 종속 여부 |
| --- | --- | --- |
| Scheduler | polling 주기, 대상 순회, query 실행 조율 | 종속 허용 |
| Source adapter | 인증된 API 호출, 응답/dimension 해석, Observation 변환 | 종속 허용 |
| `MetricObservation` | provider 간 공통 metric 계약 | 독립 |
| `ResourceMetricEvaluator` | rule 선택, threshold 판정, AlertEvent 생성 | 공통 |
| `AlertEvent` / ingestion / notification | 사건 처리와 출력 | 독립 |

## 4. Hexagonal 경계 결정

### Scheduler는 source별로 둔다

OCI와 AWS는 인증, query 문법, pagination, namespace, dimension과 호출 제한이 다르다. 이를 하나의
범용 scheduler로 먼저 추상화하면 provider 차이가 공통 인터페이스의 옵션과 조건문으로 새어 나온다.

따라서 다음과 같이 source별 진입점을 유지한다.

```text
OciMonitoringScheduler -> OciMonitoringAdapter
AwsMonitoringScheduler -> AwsCloudWatchAdapter
```

Scheduler는 source 전용 inbound adapter로 본다. OCI SDK를 직접 다루지는 않지만 현재는 구체적인
`OciMonitoringAdapter`와 query/properties에 의존한다. 이 의존성은 source 경계 안에 있으므로 허용한다.

### 인증은 source 계층이 담당한다

인증 방식은 metric 의미나 alert rule과 무관하다.

- local OCI: `~/.oci/config`의 OCI CLI profile
- prod OCI: Instance Principal
- 향후 AWS: AWS SDK credential provider chain 또는 workload identity

인증 객체와 SDK client Bean은 source config가 생성한다. Observation, evaluator, AlertEvent에는 인증
정보가 들어가지 않는다.

### Adapter가 provider 응답을 Observation으로 정규화한다

Adapter는 OCI의 metric 이름과 dimension을 공통 의미로 바꾼다.

```text
OCI CPUUtilization
  -> provider = OCI
  -> metricKind = CPU_UTILIZATION
  -> providerMetricName = CPUUtilization
  -> unit = PERCENT
```

`providerMetricName`과 `metricNamespace`는 원본 추적을 위해 보존하고, `metricKind`와 `unit`은
provider 간 rule 선택에 사용한다. 원본 payload가 필요하면 `rawPayload`에 선택적으로 보존한다.

### Evaluator는 공통으로 두고 rule을 provider별로 분리한다

`ResourceMetricEvaluator`는 특정 SDK나 source properties를 알지 않는다. 현재 utilization rule은 다음
조합으로 선택한다.

```text
provider + resourceType + metricKind + unit
```

현재 등록된 rule은 OCI MySQL CPU와 DB volume이다. 일치하는 rule이 없으면 이벤트를 만들지 않는다.
따라서 `AWS_CLOUDWATCH` observation이 들어와도 AWS rule을 명시적으로 추가하기 전에는 OCI rule로
잘못 평가되지 않는다.

AWS 지원 시에는 다음 순서로 확장한다.

1. AWS adapter가 CloudWatch 응답을 `MetricObservation`으로 변환한다.
2. 필요한 경우 `AlertSource.AWS_MONITORING`을 추가한다.
3. evaluator에 AWS의 provider/resource/metric 조합에 맞는 rule을 추가한다.
4. AWS profile 또는 resource 설정에서 threshold를 전달한다.
5. 같은 입력이 OCI rule과 AWS rule 중 하나에만 매칭되는지 테스트한다.

provider 간 metric 의미와 기준이 완전히 같더라도 rule을 명시적으로 공유한다. 이름만 같은 provider
metric을 자동으로 같은 rule로 취급하지 않는다.

### Source port는 두 번째 provider에서 추출한다

현재 scheduler는 구체 adapter에 직접 의존한다. 아직 OCI 하나뿐이라 공통 port를 먼저 만들면 실제
공통점보다 예상에 기반한 추상화가 될 가능성이 크다.

AWS 구현 시 아래 형태가 반복되는지 확인한 뒤 `MonitoringSourcePort`를 추출한다.

```text
query -> List<MetricObservation>
```

이때 port의 입력은 OCI MQL이나 CloudWatch request가 아니라 domain 수준의 resource/metric query여야
한다. provider별 query 옵션이 계속 다르면 scheduler와 adapter를 분리된 채로 유지하는 편이 낫다.

## 5. Vault와 YAML의 책임

현재 설정은 다음 원칙을 따른다.

### Vault에 두는 값

- `spring.datasource.url`, `username`, `password`
- `discord.bot-token`
- DB System OCID와 compartment OCID
- 대상의 `service`, `team`, `enabled`

DB와 Discord 값은 secret이므로 Vault에 둔다. OCID와 service/team은 반드시 secret은 아니지만 운영
대상별 값이고 배포 환경에서 중앙 관리할 필요가 있어 같은 Vault 설정으로 관리한다.

### YAML에 두는 값

- Vault secret OCID와 region
- Monitoring 활성화 여부
- local/prod OCI 인증 방식
- local/prod threshold
- Discord channel ID처럼 공개 가능한 고정 설정

Vault secret OCID는 Vault 내용을 읽기 전에 필요한 bootstrap 값이다. 실제 secret 내용이 아니므로
YAML에 둘 수 있다. threshold는 보안 정보가 아니며 운영 정책이므로 profile YAML에 명시해 코드 리뷰에서
local과 prod를 바로 비교할 수 있게 한다.

현재 profile 차이는 다음과 같다.

| 설정 | local | prod |
| --- | --- | --- |
| OCI 인증 | OCI config profile | Instance Principal |
| Polling 주기 | 1분 | 3분 |
| Query window | 5분 | 4분 |
| CPU warning/critical | 1 / 2 | 80 / 90 |
| DB volume warning/critical | 1 / 2 | 80 / 90 |

낮은 local threshold는 실제 운영 기준이 아니라 OCI 조회부터 Discord 출력까지 연결됐는지 확인하기 위한
값이다. 공통 Vault bootstrap, datasource, Discord token 주입과 Monitoring 활성화는 profile 간 동일하다.
Scheduler는 `fixedDelay`를 사용하므로 각 polling 실행이 끝난 뒤 local은 1분, prod는 3분을 기다린다.
prod query window는 4분으로 두어 실행 사이에 1분을 겹친다.
처리 시간이 길어져도 실행이 겹치지 않는다.

### 현재 DB 상태

`AlertIncident`, `AlertEventLog` entity와 Flyway migration은 향후 Incident 저장을 위해 먼저 추가됐다.
하지만 repository와 `IncidentService`는 아직 구현되지 않았고, `AlertIngestionService`는
`NotificationPort`를 바로 호출한다. 따라서 현재 AlertEvent가 FIRING되면 DB 저장 없이 Discord로 간다.

다만 JPA와 Flyway dependency가 활성화돼 있어 애플리케이션 시작 과정에서는 DB 연결을 요구한다.
운영 Pod는 VCN 내부에서 Vault datasource의 private endpoint에 접근할 수 있지만 로컬 머신에는 route가
없어 connect timeout이 발생한다. Monitoring local E2E에서는 DB/JPA/Flyway auto-configuration을 실행
옵션으로 제외한다. Incident 기능을 구현할 때 이 임시 분리를 다시 검토한다.

## 6. DB Systems를 List가 아니라 Map으로 둔 이유

초기 형태는 다음과 같은 List였다.

```yaml
db-systems:
  - id: ocid1.mysqldbsystem...
    compartment-id: ocid1.compartment...
    thresholds:
      cpu-utilization:
        warning: 80
        critical: 90
```

List는 한 property source가 각 원소를 완전하게 제공할 때는 단순하다. 하지만 Vault와 profile YAML이
한 DB System의 서로 다른 필드를 제공해야 하는 현재 구조에는 불리하다.

- List 원소는 index로 식별되므로 `db-systems[0]`의 의미가 설정마다 암묵적이다.
- profile이 일부 필드만 선언할 때 다른 property source의 같은 index와 안정적으로 합쳐진다고 기대하기
  어렵다.
- 순서가 바뀌면 다른 DB에 threshold가 적용될 위험이 있다.
- local YAML에도 OCID와 compartment를 중복 선언하게 될 수 있다.

현재는 안정적인 별칭을 key로 사용하는 Map이다.

```yaml
db-systems:
  wafflestudio-mysql:
    thresholds:
      cpu-utilization:
        warning: 80
        critical: 90
```

Vault는 같은 key 아래의 대상 정보를 제공한다.

```text
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.id
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.compartment-id
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.service
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.team
alert.oci-monitoring.mysql.db-systems.wafflestudio-mysql.enabled
```

Spring Binder는 여러 property source에서 같은 Map key 아래의 서로 다른 leaf property를 모아 하나의
`OciMysqlDbSystemProperties`로 바인딩한다. 따라서 Vault의 OCID와 YAML의 threshold가
`wafflestudio-mysql`이라는 명시적 identity를 기준으로 합쳐진다.

### List를 선택할 기준

- 순서 자체가 의미가 있다.
- 중복 항목을 허용해야 한다.
- 한 설정 출처가 각 원소의 모든 필드를 제공한다.
- profile 간 부분 override가 필요 없다.

### Map을 선택할 기준

- 각 항목에 안정적인 이름 또는 identity가 있다.
- Vault, 환경변수, profile YAML 등 여러 property source를 병합한다.
- 항목별 일부 필드만 override한다.
- 설정 diff에서 어느 리소스가 바뀌었는지 명확해야 한다.

현재 DB Systems는 Map 조건에 해당한다. key에는 긴 OCID 대신 사람이 읽을 수 있고 환경 간에도 의미가
안정적인 `wafflestudio-mysql` 같은 별칭을 사용한다. 실제 리소스 identity는 value의 `id`로 유지한다.

같은 leaf property를 Vault와 YAML 양쪽에 중복 정의해서 우선순위에 의존하지 않는다. 현재는 대상 정보는
Vault, threshold는 YAML로 leaf 책임을 분리한다. key를 변경하면 기존 항목 override가 아니라 새 항목이
되므로 rename 시 Vault와 모든 profile YAML을 함께 변경해야 한다.

## 7. 다음 확장 시 확인사항

- 새 provider adapter가 SDK 응답을 `MetricObservation`으로 완전히 정규화하는가?
- provider 원본 metric 이름이 아니라 `MetricKind`를 기준으로 rule을 선택하는가?
- rule이 provider, resource type, metric kind, unit을 충분히 좁게 검사하는가?
- 출력 `AlertSource`, fingerprint prefix와 Discord routing이 함께 추가됐는가?
- 새로운 target 설정이 Map의 안정적인 key를 사용하는가?
- secret과 운영 정책 값이 Vault/YAML 책임에 맞게 배치됐는가?
- 같은 leaf를 여러 property source에 중복 정의하지 않았는가?
- `RESOLVED`를 추가할 때 이전 상태와 fingerprint를 기준으로 같은 Incident에 연결하는가?
