package com.wafflestudio.alert.inbound.discord

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 1분마다 만료된 mute를 끝내고 채널에 만료 공지를 보낸다. */
@Component
@ConditionalOnProperty(prefix = "alert.mute", name = ["enabled"], havingValue = "true")
class MuteExpiryScheduler(
    private val handler: MuteCommandHandler,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun announceExpired() {
        runCatching { handler.announceExpired() }
            .onFailure { log.error("Failed to process expired mutes", it) }
    }
}
