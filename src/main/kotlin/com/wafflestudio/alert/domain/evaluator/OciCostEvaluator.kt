package com.wafflestudio.alert.domain.evaluator

import com.wafflestudio.alert.domain.model.AlertEvent
import com.wafflestudio.alert.domain.model.AlertSource
import com.wafflestudio.alert.domain.model.AlertStatus
import com.wafflestudio.alert.domain.model.Severity
import com.wafflestudio.alert.source.exchange.ExchangeRateProvider
import com.wafflestudio.alert.source.oci.CostBucket
import com.wafflestudio.alert.source.oci.OciCostProperties
import com.wafflestudio.alert.source.oci.WeeklyCost
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// TODO: OCI 비용 threshold / 증가율(7일 평균 대비) 판단 -> AlertEvent

@Component
class OciCostEvaluator(
    private val props: OciCostProperties,
    private val exchangeRateProvider: ExchangeRateProvider? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

    fun evaluateSpike(daily: List<CostBucket>): AlertEvent? {
        val spike = props.spike
        // 비용 정착 대기일 + 판단 대상 1일 + 비교 대상인 직전 1일
        val needed = spike.settleLagDays + 2

        if (daily.size < needed) {
            log.warn("날짜 부족")
            return null
        }

        val recent = daily.sortedBy { it.periodStart }.takeLast(needed)
        val dates =
            recent.map {
                it.periodStart.atZone(ZoneOffset.UTC).toLocalDate()
            }
        val isConsecutive =
            dates.zipWithNext().all { (previous, next) ->
                ChronoUnit.DAYS.between(previous, next) == 1L
            }
        if (!isConsecutive) {
            log.warn("OCI 비용 데이터의 UTC 날짜가 연속적이지 않아 스파이크 판단을 건너뜁니다: {}", dates)
            return null
        }

        val settled = recent.dropLast(spike.settleLagDays)
        val target = settled.last()
        val previous = settled[settled.lastIndex - 1]

        if (previous.amount <= BigDecimal.ZERO || previous.amount < spike.minAverageAmount) return null

        val ratio = target.amount.divide(previous.amount, 4, RoundingMode.HALF_UP)
        val severity =
            when {
                ratio >= spike.criticalMultiplier -> Severity.CRITICAL
                ratio >= spike.warningMultiplier -> Severity.WARNING
                else -> return null // 임계 미달이면 알림 없음
            }

        val day = dateFmt.format(target.periodStart)
        val isSgd = target.currency.equals("SGD", ignoreCase = true)
        val exchangeRate = if (isSgd) exchangeRateProvider?.getSgdToKrwRate() else null
        val targetKrw = if (isSgd && exchangeRate != null) " (약 ${exchangeRate.formatWon(target.amount)})" else ""
        val previousKrw =
            if (isSgd && exchangeRate != null) {
                " ${previous.currency} (약 ${exchangeRate.formatWon(previous.amount)})"
            } else {
                " ${previous.currency}"
            }
        val rateSuffix = if (isSgd && exchangeRate != null) "\n\n환율: ${exchangeRate.formatSummary()}" else ""

        return AlertEvent(
            source = AlertSource.OCI_COST,
            status = AlertStatus.FIRING, // firing말고 다른거 추가해야하는데 까먹을듯
            severity = severity,
            fingerprint = "oci-cost:spike:$day",
            ruleName = "oci-cost-spike",
            title = "OCI 일일 비용 급증",
            description =
                "$day 비용 ${target.amount.setScale(2, RoundingMode.HALF_UP)} ${target.currency}$targetKrw " +
                    "(전일 ${previous.amount.setScale(2, RoundingMode.HALF_UP)}$previousKrw 대비 " +
                    "${ratio.setScale(2, RoundingMode.HALF_UP)}배)$rateSuffix",
            team = "infra",
        )
    }

    fun buildWeeklySummary(
        weekly: List<WeeklyCost>,
        monthly: List<CostBucket>,
    ): AlertEvent {
        val weekFmt = DateTimeFormatter.ofPattern("MM/dd")
        val monthFmt = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC)
        val hasSgd =
            weekly.any { it.currency.equals("SGD", ignoreCase = true) } ||
                monthly.any { it.currency.equals("SGD", ignoreCase = true) }
        val exchangeRate = if (hasSgd) exchangeRateProvider?.getSgdToKrwRate() else null

        val weeklyLines =
            weekly.joinToString("\n") { w ->
                val end = w.weekStart.plusDays(6)
                val krw =
                    if (w.currency.equals("SGD", ignoreCase = true) && exchangeRate != null) {
                        " (약 ${exchangeRate.formatWon(w.amount)})"
                    } else {
                        ""
                    }
                "  ${w.weekStart.format(weekFmt)}~${end.format(weekFmt)}: " +
                    "${w.amount.setScale(2, RoundingMode.HALF_UP)} ${w.currency}$krw"
            }
        val monthlyLines =
            monthly.joinToString("\n") { m ->
                val krw =
                    if (m.currency.equals("SGD", ignoreCase = true) && exchangeRate != null) {
                        " (약 ${exchangeRate.formatWon(m.amount)})"
                    } else {
                        ""
                    }
                "  ${monthFmt.format(m.periodStart)}: " +
                    "${m.amount.setScale(2, RoundingMode.HALF_UP)} ${m.currency}$krw"
            }

        val rateSuffix = if (hasSgd && exchangeRate != null) "\n\n환율: ${exchangeRate.formatSummary()}" else ""

        val description =
            buildString {
                appendLine("[주별 추이 (최근 ${weekly.size}주)]")
                appendLine(weeklyLines)
                appendLine()
                appendLine("[월별 추이 (최근 ${monthly.size}달)]")
                append(monthlyLines)
                if (rateSuffix.isNotEmpty()) {
                    append(rateSuffix)
                }
            }

        return AlertEvent(
            source = AlertSource.OCI_COST,
            status = AlertStatus.FIRING,
            severity = Severity.INFO,
            fingerprint = "oci-cost:summary:${weekly.lastOrNull()?.weekStart}",
            ruleName = "oci-cost-weekly-summary",
            title = "OCI 비용 주간 요약",
            description = description,
            team = null,
        )
    }
}
