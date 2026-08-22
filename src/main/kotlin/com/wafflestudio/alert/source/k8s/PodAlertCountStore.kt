package com.wafflestudio.alert.source.k8s

import com.wafflestudio.alert.source.k8s.model.Pod
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

/**
 * 파드당 알림 횟수를 그 파드의 annotation에 기록한다. 앱이 재시작해도 카운터가 살아남고 별도 테이블도 필요 없다.
 *
 * 키에 접두사를 붙여 구 k8s-monitoring의 `alertCount` 와 분리했다. 같은 키를 쓰면 병행 가동 중 두 앱이 상한을
 * 나눠 갖게 되고, 무엇보다 구 앱이 알림 없이 올려둔 값을 물려받아 통보된 적 없는 파드에 대해 침묵하게 된다.
 */
@Component
@ConditionalOnK8sWatch
class PodAlertCountStore(
    @Qualifier("k8sApiRestClient") private val k8sApiRestClient: RestClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 읽지 못하면 0으로 본다 - 알림을 막지 않는 쪽으로 기운다. */
    fun read(pod: Pod): Int =
        runCatching {
            k8sApiRestClient
                .get()
                .uri("/api/v1/namespaces/{namespace}/pods/{name}", pod.namespace, pod.name)
                .retrieve()
                .body(JsonNode::class.java)
                ?.path("metadata")
                ?.path("annotations")
                ?.path(ANNOTATION_KEY)
                ?.stringOrNull()
                ?.toIntOrNull()
                ?: 0
        }.getOrElse {
            log.warn("파드 alertCount를 읽지 못했다: {}/{}", pod.namespace, pod.name, it)
            0
        }

    /**
     * `strategic-merge-patch` 는 보낸 필드만 덮어쓴다. 파드 전체를 PUT하면 다른 필드가 날아간다.
     *
     * @return 갱신 성공 여부. 실패해도 예외를 던지지 않는다 - 워처가 죽으면 안 된다.
     */
    fun write(
        pod: Pod,
        alertCount: Int,
    ): Boolean =
        runCatching {
            k8sApiRestClient
                .patch()
                .uri("/api/v1/namespaces/{namespace}/pods/{name}", pod.namespace, pod.name)
                .contentType(STRATEGIC_MERGE_PATCH)
                .body("""{"metadata":{"annotations":{"$ANNOTATION_KEY":"$alertCount"}}}""")
                .retrieve()
                .toBodilessEntity()
            true
        }.getOrElse {
            // 실패하면 카운터가 멈춰 같은 파드에 알림이 계속 나간다. 조용히 넘기면 원인을 못 찾는다.
            log.error(
                "파드 alertCount 갱신 실패 ({}/{} -> {}). ClusterRole에 pods patch 권한이 있는지 확인할 것",
                pod.namespace,
                pod.name,
                alertCount,
                it,
            )
            false
        }

    private companion object {
        const val ANNOTATION_KEY = "waffle-alert.wafflestudio.com/alert-count"
        val STRATEGIC_MERGE_PATCH: MediaType = MediaType.valueOf("application/strategic-merge-patch+json")
    }
}
