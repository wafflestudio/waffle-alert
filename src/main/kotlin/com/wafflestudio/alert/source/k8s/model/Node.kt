package com.wafflestudio.alert.source.k8s.model

import com.wafflestudio.alert.source.k8s.K8sEventType
import com.wafflestudio.alert.source.k8s.stringOrNull
import tools.jackson.databind.JsonNode

/** 파드가 실제로 올라가는 머신 한 대. */
data class Node(
    val type: K8sEventType,
    val name: String,
    val status: Status,
    val labels: Map<String, String>,
) {
    enum class Status {
        READY,
        NOT_READY,
        UNKNOWN,
    }

    val isAddedNotReady: Boolean
        get() = type == K8sEventType.ADDED && status != Status.READY

    val isDeleted: Boolean
        get() = type == K8sEventType.DELETED

    companion object {
        fun fromOrNull(event: JsonNode): Node? {
            val obj = event.path("object")
            val name = obj.path("metadata").path("name").stringOrNull() ?: return null

            return Node(
                type = K8sEventType.from(event.path("type").stringOrNull()),
                name = name,
                status =
                    when (
                        obj
                            .path("status")
                            .path("conditions")
                            .firstOrNull { it.path("type").stringOrNull() == "Ready" }
                            ?.path("status")
                            ?.stringOrNull()
                    ) {
                        "True" -> Status.READY
                        "False" -> Status.NOT_READY
                        else -> Status.UNKNOWN
                    },
                labels =
                    obj
                        .path("metadata")
                        .path("labels")
                        .properties()
                        .mapNotNull { (key, value) -> value.stringOrNull()?.let { key to it } }
                        .toMap(),
            )
        }
    }
}
