package com.wafflestudio.alert.source.k8s.model

import com.wafflestudio.alert.source.k8s.K8sEventType
import com.wafflestudio.alert.source.k8s.stringOrNull
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * 파드의 실제 상태는 [phase] 가 아니라 [containerStatuses] 에 있다. `phase=Pending` 하나로는
 * "스케줄 대기 중"인지 "이미지를 못 받아 못 뜨는 중"인지 구분할 수 없다.
 */
data class Pod(
    val type: K8sEventType,
    val namespace: String,
    val name: String,
    val phase: Phase,
    val containerStatuses: List<ContainerStatus>,
    val startTime: Instant,
) {
    /** k8s가 쓰는 실제 문자열과 이름이 일치해야 한다. */
    enum class Phase {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        UNKNOWN,
        ;

        companion object {
            fun from(raw: String?): Phase = entries.firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
        }
    }

    /** k8s가 `state` 아래에 running/waiting/terminated 중 정확히 하나를 넣어준다. */
    sealed class ContainerStatus {
        abstract val name: String
        abstract val image: String

        data class Running(
            override val name: String,
            override val image: String,
            val startedAt: Instant?,
        ) : ContainerStatus()

        data class Waiting(
            override val name: String,
            override val image: String,
            val reason: String,
            val message: String?,
        ) : ContainerStatus() {
            /** 파드가 막 뜨는 중에도 Waiting을 거치므로, 정상 기동 과정에 해당하는 reason은 제외한다. */
            val failed: Boolean get() = reason !in NORMAL_WAITING_REASONS
        }

        data class Terminated(
            override val name: String,
            override val image: String,
            val exitCode: Int,
            val reason: String?,
            val startedAt: Instant?,
            val finishedAt: Instant?,
        ) : ContainerStatus()

        /** 어디에도 안 맞는 경우. 원본 JSON을 그대로 들고 있는다. */
        data class Unknown(
            override val name: String,
            override val image: String,
            val stateJson: String,
        ) : ContainerStatus()
    }

    /** 컨테이너 중 하나라도 비정상 대기 상태면 실패로 본다. */
    val isFailed: Boolean
        get() = containerStatuses.any { it is ContainerStatus.Waiting && it.failed }

    val failureReasons: List<String>
        get() = containerStatuses.filterIsInstance<ContainerStatus.Waiting>().filter { it.failed }.map { it.reason }

    companion object {
        private val NORMAL_WAITING_REASONS = setOf("PodInitializing", "ContainerCreating")

        /** watch에는 다룰 수 없는 이벤트가 섞여 온다(BOOKMARK, 아직 스케줄되지 않은 파드 등). 그런 건 null. */
        fun fromOrNull(event: JsonNode): Pod? {
            val obj = event.path("object")
            val namespace = obj.path("metadata").path("namespace").stringOrNull() ?: return null
            val name = obj.path("metadata").path("name").stringOrNull() ?: return null
            val status = obj.path("status")
            val startTime = status.path("startTime").stringOrNull()?.toInstantOrNull() ?: return null

            return Pod(
                type = K8sEventType.from(event.path("type").stringOrNull()),
                namespace = namespace,
                name = name,
                phase = Phase.from(status.path("phase").stringOrNull()),
                // Jackson 3의 JsonNode.map() 이 Kotlin의 Iterable.map 과 충돌한다.
                containerStatuses = status.path("containerStatuses").values().map(::toContainerStatus),
                startTime = startTime,
            )
        }

        private fun toContainerStatus(node: JsonNode): ContainerStatus {
            val name = node.path("name").stringOrNull().orEmpty()
            val image = node.path("image").stringOrNull().orEmpty()
            val state = node.path("state")

            val running = state.path("running")
            if (running.isObject) {
                return ContainerStatus.Running(
                    name = name,
                    image = image,
                    startedAt = running.path("startedAt").stringOrNull()?.toInstantOrNull(),
                )
            }

            val waiting = state.path("waiting")
            if (waiting.isObject) {
                return ContainerStatus.Waiting(
                    name = name,
                    image = image,
                    reason = waiting.path("reason").stringOrNull().orEmpty(),
                    message = waiting.path("message").stringOrNull(),
                )
            }

            val terminated = state.path("terminated")
            if (terminated.isObject) {
                return ContainerStatus.Terminated(
                    name = name,
                    image = image,
                    exitCode = terminated.path("exitCode").asInt(0),
                    reason = terminated.path("reason").stringOrNull(),
                    startedAt = terminated.path("startedAt").stringOrNull()?.toInstantOrNull(),
                    finishedAt = terminated.path("finishedAt").stringOrNull()?.toInstantOrNull(),
                )
            }

            return ContainerStatus.Unknown(name = name, image = image, stateJson = state.toString())
        }
    }
}

/** 형식이 어긋나면 파싱 실패로 죽는 대신 null로 흘린다. */
internal fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
