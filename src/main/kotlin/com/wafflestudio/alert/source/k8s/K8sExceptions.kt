package com.wafflestudio.alert.source.k8s

/** 401/403. 권한이 붙기 전까지는 재시도해도 실패하므로 [K8sWatchRunner] 가 재시도 간격을 길게 잡는다. */
class K8sAuthException(
    message: String,
) : RuntimeException(message)

/** @param status 410(Gone)이면 resourceVersion 커서가 만료된 것이라 버리고 다시 시작해야 한다. */
class K8sWatchException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
