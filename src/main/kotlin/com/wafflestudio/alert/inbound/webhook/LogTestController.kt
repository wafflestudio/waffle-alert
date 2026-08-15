package com.wafflestudio.alert.inbound.webhook

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Loki(Alloy 수집 → ERROR 필터 → ApplicationErrorLog alert) 파이프라인 e2e 검증용 임시
 * 엔드포인트. 실제로 ERROR 레벨 stdout 로그(스택트레이스 포함)를 찍어서, Alloy가 이걸
 * 집어가 Loki에 적재하고 alert가 발동하는지 처음부터 끝까지 확인할 수 있다.
 */
@RestController
class LogTestController {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/v1/test/force-error-log")
    fun forceErrorLog(
        @RequestParam(required = false) message: String?,
    ): String {
        val marker = UUID.randomUUID().toString().take(8)
        try {
            throw IllegalStateException(message ?: "forced test error for Loki pipeline verification")
        } catch (e: Exception) {
            log.error("[force-error-log marker={}] test error triggered", marker, e)
        }
        return "logged with marker=$marker"
    }
}
