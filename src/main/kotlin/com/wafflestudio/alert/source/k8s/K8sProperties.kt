package com.wafflestudio.alert.source.k8s

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/** alert.k8s.* — 쿠버네티스 watch 수신부 설정 */
@Configuration
@ConfigurationProperties(prefix = "alert.k8s")
class K8sProperties {
    var enabled: Boolean = false

    /** 로컬에서는 `kubectl proxy --port=8001` 을 띄우고 `http://localhost:8001` 을 주면 인증/TLS가 필요 없다. */
    var apiUrl: String = "https://kubernetes.default.svc"

    /** 없으면(로컬) 인증 헤더 없이 나간다. */
    var tokenPath: String = "/var/run/secrets/kubernetes.io/serviceaccount/token"

    /** 있으면 이걸로 TLS를 검증하고, 없으면 기본 신뢰 저장소를 쓴다. */
    var caPath: String = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"

    /** 비워두면 클러스터 전체. Node는 네임스페이스에 속하지 않아 영향을 받지 않는다. */
    var namespace: String = ""

    var pod: Boolean = true
    var job: Boolean = true
    var node: Boolean = true

    /** 재접속 백오프. 접속에 성공하면 min으로 리셋된다. */
    var minBackoff: Duration = Duration.ofSeconds(1)
    var maxBackoff: Duration = Duration.ofSeconds(60)

    /** 이 시간이 지나면 API 서버가 커넥션을 정상 종료한다. 안 주면 서버 기본값(5~10분 랜덤). */
    var watchTimeout: Duration = Duration.ofMinutes(5)

    var connectTimeout: Duration = Duration.ofSeconds(5)

    /** watch가 아닌 일반 요청용. watch 쪽과 달리 반드시 걸어야 한다 ([K8sClientConfig] 참고). */
    var apiReadTimeout: Duration = Duration.ofSeconds(10)

    /** 실패한 파드는 몇 초마다 이벤트를 뿜으므로 상한이 없으면 알림이 폭발한다. [PodAlertCountStore] 참고. */
    var maxAlertsPerPod: Int = 3

    fun isEnabled(kind: K8sWatchKind): Boolean =
        when (kind) {
            K8sWatchKind.POD -> pod
            K8sWatchKind.JOB -> job
            K8sWatchKind.NODE -> node
        }
}

enum class K8sWatchKind {
    POD,
    JOB,
    NODE,
}
