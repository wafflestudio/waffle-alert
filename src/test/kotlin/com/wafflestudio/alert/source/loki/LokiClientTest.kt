package com.wafflestudio.alert.source.loki

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.test.assertContains
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

    @Test
    fun `namespace가 없으면 링크를 만들지 않는다`() {
        assertNull(client.grafanaExploreUrl(null, "trace-1", observedAt))
    }

    @Test
    fun `trace_id가 있으면 트레이스 기준 LogQL이 링크에 포함된다`() {
        val url = client.grafanaExploreUrl("siksha-prod", "trace-1", observedAt)!!
        val decodedQuery = decodedExpr(url)

        assertTrue(decodedQuery.contains("""{namespace="siksha-prod"}"""))
        assertContains(decodedQuery, """|= "trace-1"""")
    }

    @Test
    fun `trace_id가 none이면 namespace만으로 쿼리한다`() {
        val url = client.grafanaExploreUrl("siksha-prod", "none", observedAt)!!
        val decodedQuery = decodedExpr(url)

        assertTrue(decodedQuery.trim() == """{namespace="siksha-prod"}""")
    }

    @Test
    fun `namespace나 trace_id에 큰따옴표가 있어도 LogQL 문법이 깨지지 않게 이스케이프한다`() {
        val url = client.grafanaExploreUrl("siksha-prod", """evil" |= "injected""", observedAt)!!
        val decodedQuery = decodedExpr(url)

        // 원본 값의 큰따옴표가 \"로 이스케이프되어 LogQL 문자열 리터럴 밖으로 못 빠져나가야 한다
        // (이스케이프 안 됐다면 |= "injected"가 별도 LogQL 절로 해석되어 쿼리 구조가 깨진다).
        // decodedExpr가 JSON 파싱까지 끝낸 순수 LogQL 문자열을 반환하므로, 여기 남아있는
        // 백슬래시는 우리 코드가 추가한 LogQL 자체 이스케이프뿐이다.
        assertContains(decodedQuery, """"evil\" |= \"injected"""")
    }

    /** panes 파라미터를 URL 디코딩 + JSON 파싱해서 순수 LogQL expr 문자열을 반환한다. */
    private fun decodedExpr(url: String): String {
        val panesParam = url.substringAfter("panes=").substringBefore("&")
        val decoded = URLDecoder.decode(panesParam, StandardCharsets.UTF_8)
        val panes = ObjectMapper().readTree(decoded)
        return panes["loki-trace"]["queries"][0]["expr"].asText()
    }
}
