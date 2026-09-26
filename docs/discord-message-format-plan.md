# Discord 알림 메시지 포맷 정리 (구현 계획)

> 2026-09-25 작성. 1~5장은 waffle-world-oci#175, 이 레포의 메시지 포맷 PR로 반영했다.
> 7장 백로그는 아직 남아 있다.

## 1. 배경

현재 Discord 알림은 모든 소스가 `DiscordNotificationAdapter.formatMessage`의 공통 틀을 거친다.

```
{멘션} {이모지} [{STATUS}] {title}
severity: X · service: {ns} · resource: {name}
{description}
{추가 첨부}
```

이 틀에는 불필요하거나 중복되는 부분이 많다.

- `🔥`와 `[FIRING]`은 같은 뜻을 두 번 표시한다. K8s·OCI 알림은 항상 FIRING이라 정보가 없다.
- 메타 줄의 `service`와 `resource`는 title이나 description에 이미 들어 있는 값이다.
  `service:`에는 실제로 namespace가 들어가고, Prometheus Node/MySQL 룰에서는 `prometheus`라는
  엉뚱한 값이 찍힌다.
- `severity`는 대부분의 룰에서 룰마다 고정된 값이라 알림 종류 이상의 정보가 없다.
- RESOLVED 메시지는 필요 없다. Loki 알림은 이미 전송 전에 버리고 있다.

## 2. 새 메시지 규약

```
**{title}**          ← 굵게. namespace와 리소스 정보를 title에 넣는다
{메타 줄}            ← 설정에서 켠 필드만 표시. 기본은 모두 꺼서 줄 자체가 없다
{description}        ← null이거나 비어 있으면 생략
{추가 첨부}          ← Loki 로그와 Grafana 링크, OCI 조회 범위 (지금과 동일)
```

| 대상 | 구성 |
| --- | --- |
| 서비스팀 app·infra 채널, 공용 채널(prometheus-alert, k8s-alert, team-infra-alert) | title → description → 추가 첨부 |
| oci-monitoring, oci-cost 채널 | title → **severity** → description → 추가 첨부 |

모든 알림에서 다음을 없앤다.

- 이모지(🔥 ✅ 🔁)와 `[STATUS]` 텍스트
- 역할 멘션(`<@&roleId>`)
- RESOLVED 메시지

### title 규칙

title은 Discord에서 가장 먼저, 굵게 보이는 줄이다. **어느 namespace의 어떤 리소스에서 무슨 일이
일어났는지 title만 봐도 알 수 있어야 한다.** 메타 줄의 `service`, `resource`를 기본으로 끄기
때문에, 이 정보를 title에 넣지 않으면 메시지에서 사라진다.

### title 굵게 표시

`**{title}**`로 감싼다. title에 Discord 마크다운 문자(`*`, `_`, `~`, `` ` ``, `|`)가 들어가면
굵게 표시가 깨지거나 엉뚱하게 서식이 적용되므로, 감싸기 전에 앞에 `\`를 붙여 이스케이프한다.
지금 title에는 이런 문자가 거의 없지만(k8s 리소스 이름에는 `_`가 없다), Prometheus/Loki의
summary는 라벨 값이 그대로 들어가는 곳이라 방어해 둔다.

## 3. 메타 필드 설정

메타 줄은 없애지 않고 **설정으로 켜고 끌 수 있게** 한다. 기본은 모두 끄고, 필요한 소스만 켠다.

```yaml
alert:
  message:
    meta-fields:
      default: []                     # 기본: 메타 줄 없음
      sources:                        # AlertSource 단위로 덮어쓰기
        oci-monitoring: [severity]
        oci-cost: [severity]          # 일일 비용 급증, 주간 요약 모두 적용
```

- 표시할 수 있는 필드는 `severity`, `service`, `resource`다(`MetaField` enum). 나중에 필요하면
  enum 값을 추가하고 `formatMessage`에서 값을 꺼내는 부분만 늘리면 된다.
- 적용 순서: `sources[event.source]`가 있으면 그것을, 없으면 `default`를 쓴다.
- 표시 순서는 yml에 적은 순서와 상관없이 enum 순서(severity → service → resource)로 고정한다.
- 표시 형식은 지금과 같다: `severity: WARNING · service: ... · resource: ...`
- 필드를 켰는데 해당 값이 null이면(예: OCI Cost의 `resourceName`) 그 필드만 건너뛴다. 켠 필드가
  모두 null이면 줄 자체를 넣지 않는다.

### 설정 클래스 스케치

```kotlin
enum class MetaField { SEVERITY, SERVICE, RESOURCE }

@Configuration
@ConfigurationProperties(prefix = "alert.message.meta-fields")
class MessageFormatProperties {
    var default: List<MetaField> = emptyList()
    var sources: Map<AlertSource, List<MetaField>> = emptyMap()

    fun fieldsFor(source: AlertSource): List<MetaField> = sources[source] ?: default
}
```

Spring Boot는 enum을 느슨하게 바인딩하므로(대소문자, `-`/`_` 무시) yml에는 map 키와 값을
`oci-monitoring`, `severity`처럼 소문자 kebab-case로 적는다. 실제 yml 바인딩은
`MessageFormatPropertiesTest`에서 확인한다.

### formatMessage 스케치

```kotlin
private fun formatMessage(event: AlertEvent): String {
    val base =
        buildString {
            append("**${event.title.escapeMarkdown()}**")
            metaLine(event)?.let { append("\n$it") }
            event.description?.takeUnless { it.isBlank() }?.let { append("\n$it") }
            event.queryWindowLine()?.let { append("\n$it") }
        }
    if (!event.isApplicationErrorLog) return base
    return base + lokiContextSuffix(event)
}

private fun metaLine(event: AlertEvent): String? =
    messageFormatProperties
        .fieldsFor(event.source)
        .sorted()
        .mapNotNull { field ->
            when (field) {
                MetaField.SEVERITY -> "severity: ${event.severity}"
                MetaField.SERVICE -> event.service?.let { "service: $it" }
                MetaField.RESOURCE -> event.resourceName?.let { "resource: $it" }
            }
        }.takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
```

## 4. 작업 목록

### 1단계: waffle-world-oci

| # | 작업 | 파일 |
| --- | --- | --- |
| 1-1 | `send_resolved: true` → `false`. Prometheus와 Loki의 해소 메시지를 Alertmanager에서부터 보내지 않는다 | `argocd/prometheus/values.yaml` (`receivers.waffle-alert-webhook`) |
| 1-2 | Loki summary를 `Error log detected in {{ $labels.namespace }}/{{ $labels.pod }}`로 변경. 지금 title은 `ns/app`이라 pod 이름이 없다 | `argocd/loki/resources.yaml` |
| 1-3 | Loki `description` annotation 삭제. 매퍼가 `null`로 받고 어댑터가 그 줄을 건너뛴다 | `argocd/loki/resources.yaml` |

두 레포의 배포 순서는 상관없다. waffle-alert가 먼저 나가면 Loki 알림에 기존 description
(`ns/pod에서 에러 로그가 감지됨.`)이 남아 있어 pod 이름이 보이고, waffle-world-oci가 먼저 나가면
기존 메타 줄의 `resource:`와 새 title에 pod 이름이 들어 있다.

### 2단계: waffle-alert

| # | 작업 | 위치 |
| --- | --- | --- |
| 2-1 | 이모지와 `[STATUS]` 삭제 (`emoji` 변수와 `when` 문, 🔁 포함). `AlertStatus` enum은 유지 | `DiscordNotificationAdapter.formatMessage` |
| 2-2 | 멘션 삭제: `mentionRoleOf`, `DiscordMentionRole.kt`. `AlertEvent.team` 필드는 모델 필드라 유지 | `DiscordNotificationAdapter`, `routing/DiscordMentionRole.kt` |
| 2-3 | `MetaField` enum과 `MessageFormatProperties` 추가 | `config/` |
| 2-4 | `formatMessage` 재구성: 굵은 title → 메타 줄 → description(빈 값 생략) → 첨부. PR #21의 Loki 전용 severity 분기 삭제 | `DiscordNotificationAdapter.formatMessage` |
| 2-5 | title 마크다운 이스케이프 함수 추가 | `DiscordNotificationAdapter` |
| 2-6 | RESOLVED 건너뛰기를 모든 알림으로 확장. 지금은 Loki만 건너뛴다(`isResolvedApplicationErrorLog`). 1-1이 빠졌을 때를 대비한 방어 | `DiscordNotificationAdapter.notify` |
| 2-7 | `alert.message.meta-fields` 설정 추가 (3장) | `application.yml` |
| 2-8 | 테스트 수정·추가 (아래) | `DiscordNotificationAdapterTest` |
| 2-9 | 문서: `AlertEvent.title` KDoc에 title 규칙(2장) 기록, `notify-spec.md`의 포맷 설명 수정 | `AlertEvent.kt`, `docs/notify-spec.md` |

#### 2-8 테스트

고칠 것:

- `ApplicationErrorLog는 severity가 ... 표시하지 않는다` → 기본 설정에서는 모든 알림에 메타 줄이
  없다는 테스트로 일반화
- `워크로드 alert는 severity를 그대로 표시한다` → 삭제
- RESOLVED가 전송된다고 기대하는 테스트 → RESOLVED는 모든 소스에서 건너뛴다(`true` 반환,
  `sendMessage` 호출 없음)

추가할 것:

- `OCI_MONITORING`, `OCI_COST`는 title 바로 다음 줄에 `severity: X`가 붙는다
- 첫 줄이 `**{title}**`이다
- title에 `*`, `_`가 들어가도 이스케이프되어 굵게 표시가 깨지지 않는다
- description이 `null`이거나 `""`이면 빈 줄 없이 다음 요소가 이어진다
- 메시지에 이모지, `[FIRING]`, `<@&`가 없다

## 5. 배포 순서

순서는 상관없다(4장 참고).

1. waffle-world-oci PR 머지 → ArgoCD가 prometheus, loki를 sync했는지 확인. Alertmanager 설정은
   config-reloader가, Loki rule은 k8s-sidecar와 ruler poll(1분)이 반영하므로 재시작은 필요 없다.
2. waffle-alert PR 머지 → Deploy-prod 워크플로우가 이미지 빌드, `kustomization.yaml` 태그 업데이트,
   ArgoCD sync까지 자동으로 진행
3. 채널별 실제 메시지 확인
   - app 채널: dev namespace에서 에러 로그 발생
   - oci-monitoring: 다음 폴링(15분) 대기
   - oci-cost: 다음 스케줄 실행 대기

## 6. 결과 예시

### 서비스팀 app 채널 (`*-app-alert`, `*-dev-app-alert`)

````
**Error log detected in snutt-prod/snutt-ev-web-856d56f5ff-j8crv**
```
2026-09-25 14:05:01 ERROR c.w.s.EvController - NullPointerException: ...
```
🔗 [Grafana에서 전체 로그 보기](https://grafana.wafflestudio.com/explore?...)
````

### 서비스팀 infra 채널 (`*-infra-alert`)

K8s Pod 실패:

````
**[Pod Failed] hangsha-prod/hangsha-crawler-29824020-6kzqn**
```
Namespace: hangsha-prod
Name:      hangsha-crawler-29824020-6kzqn
Phase:     PENDING
StartTime: 2026-09-15 12:00:00 KST
Container:
  - name:    hangsha-crawler
    image:   yny.ocir.io/ax1dvc8vmenm/hangsha-prod/hangsha-batch:13
    reason:  ErrImagePull
    message: unable to pull image or OCI artifact: ... denied: Anonymous users are only allowed read access on public repos ...
```
````

K8s CronJob 실패:

````
**[Job Failed] snutt-prod/snutt-sync**
```
Namespace:      snutt-prod
CronJob:        snutt-sync
Job:            snutt-sync-29311200
Status:         Failed
StartTime:      2026-09-25 03:00:00 KST
CompletionTime: -
```
````

Prometheus 파드 메모리, PVC:

```
**Pod hangsha-prod/hangsha-api-7f9c8d-x2k4p near memory limit**
hangsha-prod/hangsha-api-7f9c8d-x2k4p memory at 96.3% of limit for 30m.
```

```
**PVC allclear-prod/data-mysql-0 storage high**
allclear-prod/data-mysql-0 at 91.2% for 5m.
```

### 공용 채널

`k8s-alert` (노드):

````
**[Node Added] 10.0.10.123**
```
Name:         10.0.10.123
Status:       NotReady
InstanceType: VM.Standard.A1.Flex
ObservedAt:   2026-09-25 14:10:00 KST (Node Added)
```
````

`prometheus-alert` (노드, MySQL):

```
**Node 10.0.10.5:9100 memory usage high**
Node 10.0.10.5:9100 memory at 92.4% for 5m.
```

```
**MySQL slow queries high**
23 slow queries in last 10m (threshold 10) on 10.0.1.20:9104.
```

### oci-monitoring 채널

```
**MySQL CPU utilization high**
severity: WARNING
wafflestudio-mysql CPU utilization is 85.3% (threshold: 80.0%).
조회 범위: 2026-09-25 13:44:00 ~ 2026-09-25 14:00:00 KST
```

### oci-cost 채널

일일 비용 급증:

```
**OCI 일일 비용 급증**
severity: WARNING
2026-09-23 비용 1.52 SGD (약 1,596원) (전일 1.10 SGD (약 1,155원) 대비 1.38배)

환율: 9월 25일, 1,050원
```

주간 요약:

```
**OCI 비용 주간 요약**
severity: INFO
[주별 추이 (최근 4주)]
  08/25~08/31: 7.20 SGD (약 7,560원)
  ...

[월별 추이 (최근 3달)]
  2026-07: 30.12 SGD (약 31,626원)
  ...

환율: 9월 25일, 1,050원
```

## 7. 백로그

### 7-1. title과 description의 중복 내용

이번 작업은 메시지 틀만 바꾸고 각 소스의 title/description 문구는 건드리지 않는다. 아래는 title과
description이 같은 내용을 반복하는 곳이다.

| 알림 | title | description에서 겹치는 부분 | 정리 방향 | 수정 위치 |
| --- | --- | --- | --- | --- |
| K8s Pod 실패 | `[Pod Failed] ns/pod` | 코드블록의 `Namespace`, `Name` 줄 | 두 줄 삭제 | waffle-alert `K8sEventMapper.podDescription` |
| K8s CronJob 실패 | `[Job Failed] ns/cronjob` | `Namespace`, `CronJob` 줄 | 두 줄 삭제, `Job` 줄은 유지 | waffle-alert `K8sEventMapper.jobDescription` |
| K8s Node 추가/삭제 | `[Node Added] name` | `Name` 줄, `ObservedAt` 끝의 `(Node Added)` | 둘 다 삭제 | waffle-alert `K8sEventMapper.nodeDescription` |
| Prometheus PodMemoryLimitHigh | `Pod ns/pod near memory limit` | 문장 앞의 `ns/pod` | `Memory at 96.3% of limit for 30m.` | waffle-world-oci `alert-rules.yaml` |
| Prometheus PVCStorageHighUsage | `PVC ns/pvc storage high` | 문장 앞의 `ns/pvc` | `Storage at 91.2% for 5m.` | waffle-world-oci `alert-rules.yaml` |
| Prometheus NodeDiskPressure | `Node x under disk pressure` | `Node x has DiskPressure=True` | `DiskPressure=True for 5m.` | waffle-world-oci `alert-rules.yaml` |
| Prometheus NodeMemoryHighUsage | `Node x memory usage high` | `Node x memory at ...` | `Memory at 92.4% for 5m.` | waffle-world-oci `alert-rules.yaml` |
| OCI Monitoring (MySQL 룰 전체) | `MySQL CPU utilization high` | `wafflestudio-mysql CPU utilization is ...` (지표 이름 반복) | title에 리소스(`on wafflestudio-mysql`)를 넣고 description은 수치만 | waffle-alert `ResourceMetricEvaluator` |

### 7-2. 그 외

- **title 형식 통일**: K8s는 `[Pod Failed] ns/pod`, Prometheus는 `Pod ns/pod near memory limit`,
  Loki는 `Error log detected in ns/pod`로 소스마다 형식이 다르다. `{무슨 일} in {ns}/{리소스}`
  같은 공통 형식을 정할지 검토.
- **K8s Pod 실패 title에 reason 넣기**: `ErrImagePull`, `CrashLoopBackOff` 같은 실패 사유가 지금은
  코드블록 안에만 있다. title에 넣으면 코드블록을 펼치지 않아도 원인 종류를 알 수 있다.
- **한국어/영어 문구 통일**: `Grafana에서 전체 로그 보기`, `조회 범위`, OCI 비용 알림 문구,
  K8s의 `... (생략)`.
- **CronJob 실패 중복 알림**: CronJob이 실패하면 Pod Failed(파드당 최대 3회)와 Job Failed가 함께
  와서 같은 장애가 여러 번 쌓인다.
- **메타 필드의 부정확한 값**: `service`를 다시 켜면 Prometheus Node/MySQL 룰은 exporter의
  namespace인 `prometheus`가, OCI Monitoring은 고정값 `OCI-DB`가 찍힌다. `service` 필드를 켜기
  전에 정리가 필요하다.
- **반복 전송**: RESOLVED를 끄더라도 Alertmanager는 `repeat_interval`(기본 6h, critical 3h,
  PodMemoryLimitHigh 1d)마다 같은 FIRING 메시지를 다시 보낸다. 반복 메시지를 구분하거나 줄일지는
  mute 설계(`mute-design.md`)와 함께 검토.
- **사용하지 않는 코드**: 멘션을 지우면 `AlertEvent.team`을 읽는 곳이 없다. `AlertStatus.REPEATED`도
  만드는 곳이 없다.
