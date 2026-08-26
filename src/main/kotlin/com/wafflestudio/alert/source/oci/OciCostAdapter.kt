package com.wafflestudio.alert.source.oci

import com.oracle.bmc.usageapi.UsageapiClient
import com.oracle.bmc.usageapi.model.RequestSummarizedUsagesDetails
import com.oracle.bmc.usageapi.requests.RequestSummarizedUsagesRequest
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Date

// TODO: OCI Cost/Usage API 조회 - daily/hourly 비용 집계
//   - 비용 데이터는 최대 48시간 지연 가능 -> 실시간 아님, 추세/이상 탐지용

data class CostBucket(
    val periodStart: Instant,
    val amount: BigDecimal,
    val currency: String,
)

data class WeeklyCost(
    val weekStart: LocalDate,
    val amount: BigDecimal,
    val currency: String,
)

@Component
class OciCostAdapter(
    private val usageapiClient: UsageapiClient,
    private val ociProperties: OciProperties,
) {
    // 최근 일별 비용
    fun fetchDailyCosts(days: Long): List<CostBucket> {
        require(days in 1..90) { "최대 90일 조회 가능" }
        val end = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val start = end.minus(days, ChronoUnit.DAYS)

        return summarize(start, end, RequestSummarizedUsagesDetails.Granularity.Daily)
    }

    fun fetchWeeklyCosts(weeks: Long): List<WeeklyCost> {
        require(weeks in 1..12) { "최대 12주" }

        val daily = fetchDailyCosts((weeks + 1) * 7)
        val today = LocalDate.now(ZoneOffset.UTC)

        return daily
            .groupBy {
                it.periodStart
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            }.filter { (weekStart, _) -> weekStart.plusDays(6).isBefore(today) } // 7일 다 지난 완결된 주만
            .map { (weekStart, days) ->
                WeeklyCost(
                    weekStart = weekStart,
                    amount = days.sumOf { it.amount },
                    currency = days.firstOrNull()?.currency ?: "",
                )
            }.sortedBy { it.weekStart }
            .takeLast(weeks.toInt())
    }

    fun fetchMonthlyCosts(months: Long): List<CostBucket> {
        require(months in 1..12) { "최대 12달" }

        val thisMonth = YearMonth.now(ZoneOffset.UTC)

        val end = thisMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val start =
            thisMonth
                .minusMonths(months)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()

        return summarize(start, end, RequestSummarizedUsagesDetails.Granularity.Monthly)
    }

    private fun summarize(
        start: Instant,
        end: Instant,
        granularity: RequestSummarizedUsagesDetails.Granularity,
    ): List<CostBucket> {
        val details =
            RequestSummarizedUsagesDetails
                .builder()
                .tenantId(ociProperties.tenantId)
                .timeUsageStarted(Date.from(start))
                .timeUsageEnded(Date.from(end))
                .granularity(granularity)
                .queryType(RequestSummarizedUsagesDetails.QueryType.Cost)
                .build()

        val request =
            RequestSummarizedUsagesRequest
                .builder()
                .requestSummarizedUsagesDetails(details)
                .build()

        return usageapiClient
            .requestSummarizedUsages(request)
            .usageAggregation
            .items
            .map {
                CostBucket(
                    periodStart = it.timeUsageStarted.toInstant(),
                    amount = it.computedAmount ?: BigDecimal.ZERO,
                    currency = it.currency ?: "",
                )
            }.sortedBy { it.periodStart }
    }
}
