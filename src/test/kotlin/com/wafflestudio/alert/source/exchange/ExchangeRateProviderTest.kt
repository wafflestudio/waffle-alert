package com.wafflestudio.alert.source.exchange

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExchangeRateProviderTest {
    @Test
    fun `Frankfurter API 응답을 정상 파싱하여 ExchangeRate를 생성한다`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/latest") { exchange ->
            val body = """{"amount":1.0,"base":"SGD","date":"2026-09-09","rates":{"KRW":1057.63}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val restClient = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()
            val properties =
                ExchangeRateProperties().apply {
                    baseUrl = "http://127.0.0.1:${server.address.port}"
                }
            val provider = ExchangeRateProvider(restClient, properties)

            val rate = provider.getSgdToKrwRate()

            assertNotNull(rate)
            assertEquals(BigDecimal("1057.63"), rate.rate)
            assertEquals(LocalDate.of(2026, 9, 9), rate.date)
            assertEquals("9월 9일, 1,058원", rate.formatSummary())
            assertEquals("10,576원", rate.formatWon(BigDecimal("10")))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `TTL 이내의 연속 호출은 캐시된 환율을 반환한다`() {
        val callCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/latest") { exchange ->
            callCount.incrementAndGet()
            val body = """{"amount":1.0,"base":"SGD","date":"2026-09-09","rates":{"KRW":1057.63}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val restClient = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()
            val properties =
                ExchangeRateProperties().apply {
                    baseUrl = "http://127.0.0.1:${server.address.port}"
                    cacheTtlHours = 12
                }
            val provider = ExchangeRateProvider(restClient, properties)

            val first = provider.getSgdToKrwRate()
            val second = provider.getSgdToKrwRate()

            assertEquals(1, callCount.get())
            assertEquals(first, second)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `API 호출 실패 시 이전 캐시를 fallback으로 반환한다`() {
        var shouldFail = false
        val callCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/latest") { exchange ->
            callCount.incrementAndGet()
            if (shouldFail) {
                exchange.sendResponseHeaders(500, 0)
                exchange.responseBody.close()
            } else {
                val body = """{"amount":1.0,"base":"SGD","date":"2026-09-09","rates":{"KRW":1057.63}}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()

        try {
            val restClient = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()
            val properties =
                ExchangeRateProperties().apply {
                    baseUrl = "http://127.0.0.1:${server.address.port}"
                    cacheTtlHours = 0 // 매번 갱신 시도하도록 TTL 0 설정
                    failureRetryMinutes = 30
                }
            val provider = ExchangeRateProvider(restClient, properties)

            val first = provider.getSgdToKrwRate()
            assertNotNull(first)

            shouldFail = true
            val second = provider.getSgdToKrwRate()
            val third = provider.getSgdToKrwRate()

            assertEquals(first, second)
            assertEquals(first, third)
            assertEquals(2, callCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `최초 호출 시 API 실패하면 null을 반환하고 예외를 던지지 않는다`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/latest") { exchange ->
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.close()
        }
        server.start()

        try {
            val restClient = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()
            val properties =
                ExchangeRateProperties().apply {
                    baseUrl = "http://127.0.0.1:${server.address.port}"
                }
            val provider = ExchangeRateProvider(restClient, properties)

            val rate = provider.getSgdToKrwRate()
            assertNull(rate)
        } finally {
            server.stop(0)
        }
    }
}
