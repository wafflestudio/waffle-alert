package com.wafflestudio.alert.source.k8s.handler

import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.k8s.ConditionalOnK8sWatch
import com.wafflestudio.alert.source.k8s.K8sEventMapper
import com.wafflestudio.alert.source.k8s.K8sEventType
import com.wafflestudio.alert.source.k8s.K8sProperties
import com.wafflestudio.alert.source.k8s.K8sWatchHandler
import com.wafflestudio.alert.source.k8s.K8sWatchKind
import com.wafflestudio.alert.source.k8s.PodAlertCountStore
import com.wafflestudio.alert.source.k8s.model.Pod
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * 파드 실패 감지. `ImagePullBackOff`, `CrashLoopBackOff`, `CreateContainerConfigError` 등이 여기서 잡힌다.
 *
 * `MODIFIED` 만 보는 것은 재접속 직후 쏟아지는 전체 스냅샷(전부 `ADDED`)을 걸러내는 장치이기도 하다.
 */
@Component
@ConditionalOnK8sWatch
class PodWatchHandler(
    props: K8sProperties,
    private val alertCountStore: PodAlertCountStore,
    private val mapper: K8sEventMapper,
    private val ingestionService: AlertIngestionService,
) : K8sWatchHandler(props) {
    override val kind = K8sWatchKind.POD
    override val resourcePath get() = scopedPath("/api/v1", "pods")

    override fun handle(event: JsonNode) {
        val pod = Pod.fromOrNull(event) ?: return
        if (pod.type != K8sEventType.MODIFIED) return
        if (!pod.isFailed) return

        val alertCount = alertCountStore.read(pod)
        if (alertCount >= props.maxAlertsPerPod) {
            log.debug("알림 상한 도달로 건너뜀: {}/{} (alertCount={})", pod.namespace, pod.name, alertCount)
            return
        }

        // 전송에 실패했으면 카운터를 올리지 않는다. 다음 이벤트에서 재시도된다.
        if (!ingestionService.ingest(mapper.toAlertEvent(pod))) {
            log.warn("파드 알림 전송 실패, 카운터를 올리지 않는다: {}/{}", pod.namespace, pod.name)
            return
        }
        alertCountStore.write(pod, alertCount + 1)
    }
}
