package com.wafflestudio.alert.source.exchange

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Component
class ExchangeRateProvider(
    private val exchangeRateRestClient: RestClient,
    private val properties: ExchangeRateProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cachedRate: ExchangeRate? = null

    @Volatile
    private var lastFetchedAt: Instant = Instant.EPOCH

    @Volatile
    private var retryAfter: Instant = Instant.EPOCH

    @Synchronized
    fun getSgdToKrwRate(): ExchangeRate? {
        val now = Instant.now()
        val currentCached = cachedRate
        val cacheTtl = Duration.ofHours(properties.cacheTtlHours)

        if (currentCached != null && Duration.between(lastFetchedAt, now) < cacheTtl) {
            return currentCached
        }

        if (now < retryAfter) {
            return currentCached
        }

        return try {
            val response =
                exchangeRateRestClient
                    .get()
                    .uri { uriBuilder ->
                        uriBuilder
                            .path("/v1/latest")
                            .queryParam("base", "SGD")
                            .queryParam("symbols", "KRW")
                            .build()
                    }.retrieve()
                    .body(FrankfurterLatestResponse::class.java)

            val krwRate = response?.rates?.get("KRW")
            if (krwRate != null && krwRate > BigDecimal.ZERO) {
                val date =
                    response.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        ?: LocalDate.now(ZoneOffset.UTC)
                val newRate = ExchangeRate(rate = krwRate, date = date)
                cachedRate = newRate
                lastFetchedAt = now
                retryAfter = Instant.EPOCH
                log.info("SGD/KRW exchange rate updated: {} ({})", krwRate, date)
                newRate
            } else {
                postponeRetry(now)
                log.warn("Failed to extract KRW rate from Frankfurter response: {}", response)
                currentCached
            }
        } catch (e: Exception) {
            postponeRetry(now)
            log.warn("Failed to fetch SGD/KRW exchange rate from Frankfurter, using cached value: {}", e.message)
            currentCached
        }
    }

    private fun postponeRetry(now: Instant) {
        retryAfter = now.plus(Duration.ofMinutes(properties.failureRetryMinutes))
    }
}
