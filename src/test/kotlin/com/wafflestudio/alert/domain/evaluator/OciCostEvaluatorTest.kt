package com.wafflestudio.alert.domain.evaluator

import com.wafflestudio.alert.source.oci.CostBucket
import com.wafflestudio.alert.source.oci.OciCostProperties
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OciCostEvaluatorTest {
    private val evaluator =
        OciCostEvaluator(
            OciCostProperties().apply {
                spike.minAverageAmount = BigDecimal.ZERO
            },
        )

    @Test
    fun `연속된 날짜이면 스파이크를 판단한다`() {
        val daily =
            (1..9).map { day ->
                cost("2026-07-${day.toString().padStart(2, '0')}", if (day == 8) "20" else "1")
            }

        assertNotNull(evaluator.evaluateSpike(daily))
    }

    @Test
    fun `스파이크는 직전 하루 비용과만 비교한다`() {
        val daily =
            listOf(
                cost("2026-07-01", "100"),
                cost("2026-07-02", "100"),
                cost("2026-07-03", "1"),
                cost("2026-07-04", "2"),
                cost("2026-07-05", "1"),
            )

        assertNotNull(evaluator.evaluateSpike(daily))
    }

    @Test
    fun `날짜가 누락되면 스파이크 판단을 건너뛴다`() {
        val daily =
            listOf(1, 2, 3, 4, 5, 6, 8, 10, 11).map { day ->
                cost("2026-07-${day.toString().padStart(2, '0')}", if (day == 10) "20" else "1")
            }

        assertNull(evaluator.evaluateSpike(daily))
    }

    @Test
    fun `스파이크 발생 시 환율 정보가 있으면 원화 환산과 환율 요약이 포함된다`() {
        val exchangeRateProvider = io.mockk.mockk<com.wafflestudio.alert.source.exchange.ExchangeRateProvider>()
        io.mockk.every { exchangeRateProvider.getSgdToKrwRate() } returns
            com.wafflestudio.alert.source.exchange.ExchangeRate(
                rate = BigDecimal("1058.00"),
                date = java.time.LocalDate.of(2026, 7, 8),
            )

        val evaluatorWithRate =
            OciCostEvaluator(
                props =
                    OciCostProperties().apply {
                        spike.minAverageAmount = BigDecimal.ZERO
                    },
                exchangeRateProvider = exchangeRateProvider,
            )

        val daily =
            (1..9).map { day ->
                cost("2026-07-${day.toString().padStart(2, '0')}", if (day == 8) "20" else "1")
            }

        val event = evaluatorWithRate.evaluateSpike(daily)
        assertNotNull(event)
        val description = assertNotNull(event.description)
        kotlin.test.assertTrue(description.contains("20.00 SGD (약 21,160원)"))
        kotlin.test.assertTrue(description.contains("전일 1.00 SGD (약 1,058원) 대비"))
        kotlin.test.assertTrue(description.endsWith("환율: 7월 8일, 1,058원"))
    }

    @Test
    fun `주간 요약 생성 시 환율 정보가 있으면 원화 환산과 환율 요약이 포함된다`() {
        val exchangeRateProvider = io.mockk.mockk<com.wafflestudio.alert.source.exchange.ExchangeRateProvider>()
        io.mockk.every { exchangeRateProvider.getSgdToKrwRate() } returns
            com.wafflestudio.alert.source.exchange.ExchangeRate(
                rate = BigDecimal("1058.00"),
                date = java.time.LocalDate.of(2026, 9, 9),
            )

        val evaluatorWithRate =
            OciCostEvaluator(
                props = OciCostProperties(),
                exchangeRateProvider = exchangeRateProvider,
            )

        val weekly =
            listOf(
                com.wafflestudio.alert.source.oci.WeeklyCost(
                    weekStart = java.time.LocalDate.of(2026, 8, 24),
                    amount = BigDecimal("15.00"),
                    currency = "SGD",
                ),
            )
        val monthly =
            listOf(
                CostBucket(
                    periodStart = Instant.parse("2026-08-01T00:00:00Z"),
                    amount = BigDecimal("60.00"),
                    currency = "SGD",
                ),
            )

        val event = evaluatorWithRate.buildWeeklySummary(weekly, monthly)
        val description = assertNotNull(event.description)
        kotlin.test.assertTrue(description.contains("15.00 SGD (약 15,870원)"))
        kotlin.test.assertTrue(description.contains("60.00 SGD (약 63,480원)"))
        kotlin.test.assertTrue(description.endsWith("환율: 9월 9일, 1,058원"))
    }

    @Test
    fun `SGD 비용이 아니면 환율을 조회하지 않는다`() {
        val exchangeRateProvider = io.mockk.mockk<com.wafflestudio.alert.source.exchange.ExchangeRateProvider>()
        val evaluatorWithRate =
            OciCostEvaluator(
                props = OciCostProperties(),
                exchangeRateProvider = exchangeRateProvider,
            )

        evaluatorWithRate.evaluateSpike(
            (1..9).map { day ->
                CostBucket(
                    periodStart = Instant.parse("2026-07-${day.toString().padStart(2, '0')}T00:00:00Z"),
                    amount = BigDecimal(if (day == 8) "20" else "1"),
                    currency = "USD",
                )
            },
        )
        evaluatorWithRate.buildWeeklySummary(
            weekly =
                listOf(
                    com.wafflestudio.alert.source.oci.WeeklyCost(
                        weekStart = java.time.LocalDate.of(2026, 8, 24),
                        amount = BigDecimal("15.00"),
                        currency = "USD",
                    ),
                ),
            monthly = emptyList(),
        )

        io.mockk.verify(exactly = 0) { exchangeRateProvider.getSgdToKrwRate() }
    }

    private fun cost(
        date: String,
        amount: String,
    ) = CostBucket(
        periodStart = Instant.parse("${date}T00:00:00Z"),
        amount = BigDecimal(amount),
        currency = "SGD",
    )
}
