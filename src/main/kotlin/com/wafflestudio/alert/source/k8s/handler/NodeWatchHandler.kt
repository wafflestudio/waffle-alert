package com.wafflestudio.alert.source.k8s.handler

import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.k8s.ConditionalOnK8sWatch
import com.wafflestudio.alert.source.k8s.K8sEventMapper
import com.wafflestudio.alert.source.k8s.K8sProperties
import com.wafflestudio.alert.source.k8s.K8sWatchHandler
import com.wafflestudio.alert.source.k8s.K8sWatchKind
import com.wafflestudio.alert.source.k8s.model.Node
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * 노드 추가/삭제 감지.
 *
 * 여기서 보는 신호는 `ADDED`/`DELETED` 라서 Pod 쪽의 "MODIFIED만 본다" 안전장치가 통하지 않는다.
 * 재접속 시 스냅샷 재수신은 [K8sWatchHandler] 의 resourceVersion 커서가 막아준다.
 */
@Component
@ConditionalOnK8sWatch
class NodeWatchHandler(
    props: K8sProperties,
    private val mapper: K8sEventMapper,
    private val ingestionService: AlertIngestionService,
) : K8sWatchHandler(props) {
    override val kind = K8sWatchKind.NODE

    override val resourcePath = "/api/v1/nodes"

    override fun handle(event: JsonNode) {
        val node = Node.fromOrNull(event) ?: return

        val change =
            when {
                node.isAddedNotReady -> K8sEventMapper.NodeChange.ADDED
                node.isDeleted -> K8sEventMapper.NodeChange.DELETED
                else -> return
            }

        ingestionService.ingest(mapper.toAlertEvent(node, change))
    }
}
