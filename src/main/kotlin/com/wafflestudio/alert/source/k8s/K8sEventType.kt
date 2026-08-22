package com.wafflestudio.alert.source.k8s

/** watch 이벤트의 `type`. BOOKMARK/ERROR 등은 [UNKNOWN] 으로 떨어진다. */
enum class K8sEventType {
    ADDED,
    MODIFIED,
    DELETED,
    UNKNOWN,
    ;

    companion object {
        fun from(raw: String?): K8sEventType = entries.firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
    }
}
