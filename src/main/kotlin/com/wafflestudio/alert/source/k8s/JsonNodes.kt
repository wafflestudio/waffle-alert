package com.wafflestudio.alert.source.k8s

import tools.jackson.databind.JsonNode

/** watch 이벤트는 필드가 통째로 빠져 있는 경우가 흔해서, 파서는 전부 "없으면 null" 로 다룬다. */
internal fun JsonNode.stringOrNull(): String? = if (isString) stringValue() else null
