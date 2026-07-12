package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.domain.model.AlertEvent

/** source/evaluator가 Slack/Discord 세부 구현을 모르게 하는 port. AlertEvent만 채워서 넘기면 된다. */
interface NotificationPort {
    fun notify(event: AlertEvent)
}
