package com.wafflestudio.alert.inbound.discord

import java.time.Duration

/** /mute duration 선택지. 무기한 mute는 없다. */
enum class MuteDuration(
    val label: String,
    val duration: Duration,
) {
    MINUTES_30("30m", Duration.ofMinutes(30)),
    HOUR_1("1h", Duration.ofHours(1)),
    HOURS_6("6h", Duration.ofHours(6)),
    HOURS_24("24h", Duration.ofHours(24)),
    DAYS_7("7d", Duration.ofDays(7)),
    ;

    companion object {
        fun fromLabel(label: String?): MuteDuration? = entries.firstOrNull { it.label == label }
    }
}
