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
    fun `날짜가 누락되면 스파이크 판단을 건너뛴다`() {
        val daily =
            listOf(1, 2, 3, 4, 5, 6, 8, 9, 10).map { day ->
                cost("2026-07-${day.toString().padStart(2, '0')}", if (day == 9) "20" else "1")
            }

        assertNull(evaluator.evaluateSpike(daily))
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
