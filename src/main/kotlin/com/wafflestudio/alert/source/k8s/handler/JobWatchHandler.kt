package com.wafflestudio.alert.source.k8s.handler

import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.k8s.ConditionalOnK8sWatch
import com.wafflestudio.alert.source.k8s.K8sEventMapper
import com.wafflestudio.alert.source.k8s.K8sEventType
import com.wafflestudio.alert.source.k8s.K8sProperties
import com.wafflestudio.alert.source.k8s.K8sWatchHandler
import com.wafflestudio.alert.source.k8s.K8sWatchKind
import com.wafflestudio.alert.source.k8s.model.Job
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * CronJob이 만든 Job의 실패 감지.
 *
 * Pod와 달리 횟수 상한이 없다. Job은 한 번 실패하면 끝나는 일회성 리소스라 이벤트가 반복되지 않는다.
 */
@Component
@ConditionalOnK8sWatch
class JobWatchHandler(
    props: K8sProperties,
    private val mapper: K8sEventMapper,
    private val ingestionService: AlertIngestionService,
) : K8sWatchHandler(props) {
    override val kind = K8sWatchKind.JOB
    override val resourcePath get() = scopedPath("/apis/batch/v1", "jobs")

    override fun handle(event: JsonNode) {
        val job = Job.fromOrNull(event) ?: return
        if (job.type != K8sEventType.MODIFIED) return
        if (!job.isFailedCronJob) return

        ingestionService.ingest(mapper.toAlertEvent(job))
    }
}
