package com.wafflestudio.alert.source.loki

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LokiClientTest {
    private val properties =
        LokiProperties().apply {
            grafanaBaseUrl = "https://grafana.wafflestudio.com"
            queryWindowMinutes = 4
        }
    private val client = LokiClient(mockk<RestClient>(relaxed = true), properties, ObjectMapper())
    private val observedAt = Instant.parse("2026-08-15T00:00:00Z")

    /**
     * fetchLogLines가 실제로 만드는 요청 URI를 mock 없이 검증한다. 위 client(RestClient가
     * mockk라 URI를 실제로 빌드하지 않음)로는 절대 못 잡는 버그가 있었다 - LogQL 쿼리
     * 문자열의 리터럴 "{...}"를 Spring의 UriBuilder가 미해결 URI 템플릿 변수로 오인해서
     * queryParam(name, logql)로 직접 넣으면 매 호출 IllegalArgumentException이 났다
     * (운영에서 실측). 진짜 내장 HTTP 서버로 실제 요청 URI까지 확인해야 이 클래스의
     * 회귀를 잡을 수 있다.
     */
    @Test
    fun `LogQL의 중괄호가 URI 템플릿 변수로 오인되지 않고 실제 요청이 나간다`() {
        var capturedQuery: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/loki/api/v1/query_range") { exchange ->
            capturedQuery = exchange.requestURI.rawQuery
            val body = """{"status":"success","data":{"resultType":"streams","result":[]}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val realRestClient = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()
            val realClient = LokiClient(realRestClient, properties, ObjectMapper())

            val result = realClient.fetchLogLines("waffle-alert-prod", observedAt)

            assertEquals(emptyList(), result)
            assertTrue(capturedQuery != null, "요청이 서버에 도달하지 못했다 (URI 빌드 단계에서 여전히 실패 중일 가능성)")
            val rawQueryValue = capturedQuery!!.substringAfter("query=").substringBefore("&")
            val decodedQuery = URLDecoder.decode(rawQueryValue, StandardCharsets.UTF_8)
            assertTrue(decodedQuery.startsWith("{namespace=\"waffle-alert-prod\"}"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `namespace가 없으면 링크를 만들지 않는다`() {
        assertNull(client.grafanaExploreUrl(null, observedAt))
    }

    @Test
    fun `namespace 기준 LogQL이 링크에 포함된다`() {
        val url = client.grafanaExploreUrl("siksha-prod", observedAt)!!
        val decodedQuery = decodedExpr(url)

        assertTrue(decodedQuery.trim() == """{namespace="siksha-prod"}""")
    }

    @Test
    fun `namespace에 큰따옴표가 있어도 LogQL 문법이 깨지지 않게 이스케이프한다`() {
        val url = client.grafanaExploreUrl("""evil" |= "injected""", observedAt)!!
        val decodedQuery = decodedExpr(url)

        // 원본 값의 큰따옴표가 \"로 이스케이프되어 LogQL 문자열 리터럴 밖으로 못 빠져나가야 한다
        // (이스케이프 안 됐다면 |= "injected"가 별도 LogQL 절로 해석되어 쿼리 구조가 깨진다).
        // decodedExpr가 JSON 파싱까지 끝낸 순수 LogQL 문자열을 반환하므로, 여기 남아있는
        // 백슬래시는 우리 코드가 추가한 LogQL 자체 이스케이프뿐이다.
        assertContains(decodedQuery, """"evil\" |= \"injected""")
    }

    /** panes 파라미터를 URL 디코딩 + JSON 파싱해서 순수 LogQL expr 문자열을 반환한다. */
    private fun decodedExpr(url: String): String {
        val panesParam = url.substringAfter("panes=").substringBefore("&")
        val decoded = URLDecoder.decode(panesParam, StandardCharsets.UTF_8)
        val panes = ObjectMapper().readTree(decoded)
        return panes["loki-logs"]["queries"][0]["expr"].asText()
    }
}
