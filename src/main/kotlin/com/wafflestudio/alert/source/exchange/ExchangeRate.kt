package com.wafflestudio.alert.source.exchange

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

data class ExchangeRate(
    val rate: BigDecimal,
    val date: LocalDate,
) {
    fun formatWon(amount: BigDecimal): String {
        val won = amount.multiply(rate).setScale(0, RoundingMode.HALF_UP)
        return "${NumberFormat.getNumberInstance(Locale.KOREA).format(won)}원"
    }

    fun formatSummary(): String {
        val formattedRate = NumberFormat.getNumberInstance(Locale.KOREA).format(rate.setScale(0, RoundingMode.HALF_UP))
        return "${date.monthValue}월 ${date.dayOfMonth}일, ${formattedRate}원"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FrankfurterLatestResponse(
    val amount: BigDecimal? = null,
    val base: String? = null,
    val date: String? = null,
    val rates: Map<String, BigDecimal> = emptyMap(),
)
