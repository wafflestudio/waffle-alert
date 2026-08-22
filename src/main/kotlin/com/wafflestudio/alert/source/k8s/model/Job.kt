package com.wafflestudio.alert.source.k8s.model

import com.wafflestudio.alert.source.k8s.K8sEventType
import com.wafflestudio.alert.source.k8s.stringOrNull
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * CronJob(스케줄) → Job(1회 실행) → Pod 구조라, 알림에는 매번 이름이 바뀌는 Job 이름보다 [cronJobName] 이 쓸모 있다.
 */
data class Job(
    val type: K8sEventType,
    val namespace: String,
    val name: String,
    val cronJobName: String?,
    val status: Status,
    val startTime: Instant,
    val completionTime: Instant?,
) {
    enum class Status {
        COMPLETE,
        FAILED,
        UNKNOWN,
        ;

        companion object {
            fun from(raw: String?): Status = entries.firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
        }
    }

    val isFailedCronJob: Boolean
        get() = cronJobName != null && status == Status.FAILED

    companion object {
        fun fromOrNull(event: JsonNode): Job? {
            val obj = event.path("object")
            val metadata = obj.path("metadata")
            val namespace = metadata.path("namespace").stringOrNull() ?: return null
            val name = metadata.path("name").stringOrNull() ?: return null
            val status = obj.path("status")
            val startTime = status.path("startTime").stringOrNull()?.toInstantOrNull() ?: return null

            return Job(
                type = K8sEventType.from(event.path("type").stringOrNull()),
                namespace = namespace,
                name = name,
                cronJobName =
                    metadata
                        .path("ownerReferences")
                        .firstOrNull { it.path("kind").stringOrNull() == "CronJob" }
                        ?.path("name")
                        ?.stringOrNull(),
                status = Status.from(activeConditionType(status)),
                startTime = startTime,
                completionTime = status.path("completionTime").stringOrNull()?.toInstantOrNull(),
            )
        }

        /** condition 목록에는 지금 참이 아닌 항목도 남아 있으므로 `status == "True"` 인 것만 골라야 한다. */
        private fun activeConditionType(status: JsonNode): String? =
            status
                .path("conditions")
                .firstOrNull { it.path("status").stringOrNull() == "True" }
                ?.path("type")
                ?.stringOrNull()
    }
}
