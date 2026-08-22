package com.wafflestudio.alert.source.k8s

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode

/**
 * 리소스 한 종류(pod / job / node)를 담당한다.
 *
 * watch를 그냥 걸면 API 서버는 현존하는 리소스 전부를 `ADDED` 로 한 번 쏟아준 뒤 실시간 이벤트로 넘어간다.
 * 5분마다 재접속하는 워처에서 이건 곧 알림 폭발이다. `resourceVersion` 은 "몇 번째 변경까지 봤는지" 커서라,
 * 이벤트마다 기억해뒀다가 재접속에 넘기면 그 이후 변경분만 받는다.
 */
abstract class K8sWatchHandler(
    protected val props: K8sProperties,
) {
    protected val log = LoggerFactory.getLogger(javaClass)

    abstract val kind: K8sWatchKind

    /** watch를 걸 경로. 쿼리스트링은 [path] 가 붙여준다. */
    protected abstract val resourcePath: String

    /** `/api/v1/pods` 또는 `alert.k8s.namespace` 가 있으면 `/api/v1/namespaces/{ns}/pods`. */
    protected fun scopedPath(
        apiPrefix: String,
        resource: String,
    ): String =
        if (props.namespace.isBlank()) {
            "$apiPrefix/$resource"
        } else {
            "$apiPrefix/namespaces/${props.namespace}/$resource"
        }

    protected abstract fun handle(event: JsonNode)

    @Volatile
    private var lastResourceVersion: String? = null

    fun path(): String =
        buildString {
            append(resourcePath)
            append("?watch=1")
            append("&timeoutSeconds=").append(props.watchTimeout.seconds)
            lastResourceVersion?.let { append("&resourceVersion=").append(it) }
        }

    fun onEvent(event: JsonNode) {
        event
            .path("object")
            .path("metadata")
            .path("resourceVersion")
            .stringOrNull()
            ?.let { lastResourceVersion = it }

        handle(event)
    }

    /** 410 Gone. 커서가 낡아 API 서버가 그 지점의 변경 이력을 이미 버린 상태라 처음부터 다시 시작한다. */
    fun resetResourceVersion() {
        log.warn("{} resourceVersion이 만료되어(410) 커서를 초기화한다", kind)
        lastResourceVersion = null
    }
}
