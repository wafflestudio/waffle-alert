package com.wafflestudio.alert.source.scheduler

import com.wafflestudio.alert.domain.evaluator.OciCostEvaluator
import com.wafflestudio.alert.domain.service.AlertIngestionService
import com.wafflestudio.alert.source.oci.OciCostAdapter
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OciCostScheduler(
    private val adapter: OciCostAdapter,
    private val evaluator: OciCostEvaluator,
    private val ingestionService: AlertIngestionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 실행 시각은 KST이고, 비용의 일별 집계 경계는 OCI Usage API 기준인 UTC를 사용한다.
    // 09:00 KST는 00:00 UTC이므로 별도의 날짜 변환 없이 UTC 일별 비용을 조회한다.
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    fun checkSpike() {
        try {
            val daily = adapter.fetchDailyCosts(14)
            val event = evaluator.evaluateSpike(daily)
            log.info("OCI cost spike check done: days={}, alert={}", daily.size, event != null)
            if (event != null) ingestionService.ingest(event)
        } catch (e: Exception) {
            log.error("OCI cost spike check failed", e)
        }
    }

    /** 매주 수요일 아침 9시(KST) - 최근 3주 주별 + 3달 월별 비용 요약 발송.
     *  월요일이 아닌 이유: 직전 주 일요일 비용이 최대 48h 지연될 수 있어, 72h 정착된 수요일에 발송 */
    @Scheduled(cron = "0 0 9 * * WED", zone = "Asia/Seoul")
    fun sendWeeklySummary() {
        try {
            val weekly = adapter.fetchWeeklyCosts(3)
            val monthly = adapter.fetchMonthlyCosts(3)
            val event = evaluator.buildWeeklySummary(weekly, monthly)
            ingestionService.ingest(event)
            log.info("OCI cost weekly summary sent: weeks={}, months={}", weekly.size, monthly.size)
        } catch (e: Exception) {
            log.error("OCI cost weekly summary failed", e)
        }
    }
}
