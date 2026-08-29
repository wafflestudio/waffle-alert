package com.wafflestudio.alert.source.k8s

import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.Severity
import com.wafflestudio.alert.source.k8s.model.Job
import com.wafflestudio.alert.source.k8s.model.Node
import com.wafflestudio.alert.source.k8s.model.Pod
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * k8s 도메인 객체 → 공통 [AlertEvent]. `AlertmanagerEventMapper` 와 같은 역할이다.
 *
 * [AlertEvent.service] 에 namespace를 넣으면 기존 `RoutingPolicy` 가 팀 채널로 라우팅해준다.
 */
@Component
class K8sEventMapper {
    fun toAlertEvent(pod: Pod): AlertEvent {
        val reasons = pod.failureReasons
        return AlertEvent(
            source = AlertSource.K8S,
            status = AlertStatus.FIRING,
            severity = Severity.CRITICAL,
            fingerprint = "k8s/pod/${pod.namespace}/${pod.name}/${reasons.firstOrNull() ?: "Unknown"}",
            ruleName = RULE_POD_FAILED,
            title = "[Pod Failed] ${pod.namespace}/${pod.name}",
            description = podDescription(pod),
            service = pod.namespace,
            resourceType = "Pod",
            resourceName = pod.name,
            observedAt = Instant.now(),
            labels =
                mapOf(
                    "kind" to "Pod",
                    "namespace" to pod.namespace,
                    "phase" to pod.phase.name,
                    "reasons" to reasons.joinToString(","),
                ),
        )
    }

    fun toAlertEvent(job: Job): AlertEvent =
        AlertEvent(
            source = AlertSource.K8S,
            status = AlertStatus.FIRING,
            severity = Severity.WARNING,
            fingerprint = "k8s/job/${job.namespace}/${job.name}",
            ruleName = RULE_JOB_FAILED,
            title = "[Job Failed] ${job.namespace}/${job.cronJobName}",
            description = jobDescription(job),
            service = job.namespace,
            resourceType = "Job",
            resourceName = job.name,
            observedAt = Instant.now(),
            labels =
                mapOf(
                    "kind" to "Job",
                    "namespace" to job.namespace,
                    "cronJob" to (job.cronJobName ?: ""),
                ),
        )

    fun toAlertEvent(
        node: Node,
        changed: NodeChange,
    ): AlertEvent =
        AlertEvent(
            source = AlertSource.K8S,
            status = AlertStatus.FIRING,
            severity = Severity.INFO,
            fingerprint = "k8s/node/${node.name}/${changed.name}",
            ruleName = changed.ruleName,
            title = "[${changed.label}] ${node.name}",
            description = nodeDescription(node, changed),
            // 노드는 namespace에 속하지 않는다. 비워두면 팀 매핑을 건너뛰고 기본 채널로 간다.
            service = null,
            resourceType = "Node",
            resourceName = node.name,
            observedAt = Instant.now(),
            labels = mapOf("kind" to "Node") + node.labels,
        )

    private fun podDescription(pod: Pod): String =
        codeBlock(
            buildString {
                appendLine("Namespace: ${pod.namespace}")
                appendLine("Name:      ${pod.name}")
                appendLine("Phase:     ${pod.phase}")
                appendLine("StartTime: ${pod.startTime.toKst()}")
                appendLine("Container:")
                pod.containerStatuses
                    .filterIsInstance<Pod.ContainerStatus.Waiting>()
                    .filter { it.failed }
                    .forEach {
                        appendLine("  - name:    ${it.name}")
                        appendLine("    image:   ${it.image}")
                        appendLine("    reason:  ${it.reason}")
                        appendLine("    message: ${it.message.orDash().truncate(MAX_MESSAGE_LENGTH)}")
                    }
            }.trimEnd(),
        )

    private fun jobDescription(job: Job): String =
        codeBlock(
            buildString {
                appendLine("Namespace:      ${job.namespace}")
                appendLine("CronJob:        ${job.cronJobName.orDash()}")
                appendLine("Job:            ${job.name}")
                appendLine("Status:         ${job.status}")
                appendLine("StartTime:      ${job.startTime.toKst()}")
                append("CompletionTime: ${job.completionTime?.toKst().orDash()}")
            },
        )

    private fun nodeDescription(
        node: Node,
        changed: NodeChange,
    ): String =
        codeBlock(
            buildString {
                appendLine("Name:         ${node.name}")
                appendLine("Status:       ${node.status}")
                appendLine("InstanceType: ${node.labels[INSTANCE_TYPE_LABEL].orDash()}")
                append("ObservedAt:   ${Instant.now().toKst()} (${changed.label})")
            },
        )

    /** Discord 본문 상한(2000자)을 넘기면 전송 자체가 실패한다. 잘려도 도착하는 쪽이 낫다. */
    private fun codeBlock(body: String) = "```\n${body.truncate(MAX_DESCRIPTION_LENGTH)}\n```"

    private fun String?.orDash(): String = if (isNullOrBlank()) "-" else this

    private fun String.truncate(max: Int): String = if (length <= max) this else take(max) + "... (생략)"

    private fun Instant.toKst(): String = KST_FORMAT.format(atZone(SEOUL))

    enum class NodeChange(
        val label: String,
        val ruleName: String,
    ) {
        ADDED("Node Added", "K8sNodeAdded"),
        DELETED("Node Deleted", "K8sNodeDeleted"),
    }

    private companion object {
        const val RULE_POD_FAILED = "K8sPodFailed"
        const val RULE_JOB_FAILED = "K8sJobFailed"
        const val INSTANCE_TYPE_LABEL = "node.kubernetes.io/instance-type"

        /** ImagePullBackOff의 message는 레지스트리 오류 원문이라 400자를 넘기도 한다. */
        const val MAX_MESSAGE_LENGTH = 800
        const val MAX_DESCRIPTION_LENGTH = 1500
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val KST_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'KST'").withZone(SEOUL)
    }
}
