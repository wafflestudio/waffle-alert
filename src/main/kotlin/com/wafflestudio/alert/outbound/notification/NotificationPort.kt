package com.wafflestudio.alert.outbound.notification

import com.wafflestudio.alert.domain.model.AlertEvent

/** source/evaluator가 Slack/Discord 세부 구현을 모르게 하는 port. AlertEvent만 채워서 넘기면 된다. */
interface NotificationPort {
    /**
     * @return 전송에 성공했으면 true. 실패해도 예외를 던지지 않는다 - 알림 전송 실패로 호출자(워처 루프,
     *   스케줄러)가 죽으면 안 되기 때문이다. 재시도 여부를 판단해야 하는 source는 이 값을 보면 된다
     *   (k8s Pod 알림은 false일 때 카운터를 올리지 않고 다음 이벤트에 재시도한다).
     */
    fun notify(event: AlertEvent): Boolean
}
