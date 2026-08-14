package com.wafflestudio.alert.source.loki

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Loki HTTP API(/loki/api/v1/query_range)를 재쿼리해 alert에 딸린 로그 원문을 가져오고,
 * Grafana Explore 딥링크를 만든다. trace_id가 있으면 정밀 조회, 없으면("none") namespace로
 * 스코프를 좁힌 시간창 조회로 폴백한다.
 */
@Component
class LokiClient(
    private val lokiRestClient: RestClient,
    private val lokiProperties: LokiProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param traceId "none"이면 trace_id 없는 것으로 간주해 namespace+시간창 폴백 조회.
     * @param observedAt alert가 관측된 시각. 쿼리 시간창의 기준.
     */
    fun fetchLogLines(
        namespace: String?,
        traceId: String?,
        observedAt: Instant,
    ): List<String> {
        val logql = buildQuery(namespace, traceId) ?: return emptyList()
        val window = queryWindow(observedAt)

        return try {
            val response =
                lokiRestClient
                    .get()
                    .uri { uriBuilder ->
                        uriBuilder
                            .path("/loki/api/v1/query_range")
                            .queryParam("query", logql)
                            .queryParam("start", window.startNanos)
                            .queryParam("end", window.endNanos)
                            .queryParam("limit", lokiProperties.maxLines)
                            .queryParam("direction", "forward")
                            .build()
                    }.retrieve()
                    .body(LokiQueryRangeResponse::class.java)

            response
                ?.data
                ?.result
                .orEmpty()
                .flatMap { stream -> stream.values.map { it[1] } }
                .take(lokiProperties.maxLines)
        } catch (e: Exception) {
            log.error("Failed to query Loki for namespace={}, traceId={}", namespace, traceId, e)
            emptyList()
        }
    }

    fun grafanaExploreUrl(
        namespace: String?,
        traceId: String?,
        observedAt: Instant,
    ): String? {
        val logql = buildQuery(namespace, traceId) ?: return null
        val window = queryWindow(observedAt)

        // Grafana Explore URL 구조(panes 파라미터에 URL-encoded JSON): datasource uid는
        // argocd/prometheus/loki-datasource.yaml에서 "loki"로 고정해뒀다 (고정 안 하면
        // provisioning마다 uid가 바뀌어 링크가 깨짐). ObjectMapper로 직렬화해 수동 문자열
        // 조립에서 생기는 JSON 이스케이프 버그를 피한다.
        val panes =
            mapOf(
                "loki-trace" to
                    mapOf(
                        "datasource" to "loki",
                        "queries" to
                            listOf(
                                mapOf(
                                    "refId" to "A",
                                    "datasource" to mapOf("type" to "loki", "uid" to "loki"),
                                    "expr" to logql,
                                    "queryType" to "range",
                                ),
                            ),
                        "range" to
                            mapOf(
                                "from" to window.startMillis.toString(),
                                "to" to window.endMillis.toString(),
                            ),
                    ),
            )
        val panesJson = objectMapper.writeValueAsString(panes)
        val encodedPanes = URLEncoder.encode(panesJson, StandardCharsets.UTF_8)
        return "${lokiProperties.grafanaBaseUrl}/explore?schemaVersion=1&panes=$encodedPanes&orgId=1"
    }

    private fun buildQuery(
        namespace: String?,
        traceId: String?,
    ): String? {
        if (namespace.isNullOrBlank()) {
            log.warn("Cannot build Loki query without namespace")
            return null
        }
        val safeNamespace = escapeLogQlString(namespace)
        return if (!traceId.isNullOrBlank() && traceId != "none") {
            """{namespace="$safeNamespace"} |= "${escapeLogQlString(traceId)}""""
        } else {
            """{namespace="$safeNamespace"}"""
        }
    }

    /** LogQL 문자열 리터럴 안에 들어갈 값의 큰따옴표/백슬래시를 이스케이프한다 (쿼리 문법 깨짐 방지). */
    private fun escapeLogQlString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun queryWindow(observedAt: Instant): QueryWindow {
        val halfWindowMinutes = lokiProperties.queryWindowMinutes.coerceAtLeast(1) / 2
        val start = observedAt.minusSeconds(halfWindowMinutes.coerceAtLeast(1) * 60)
        val end = observedAt.plusSeconds(halfWindowMinutes.coerceAtLeast(1) * 60)
        return QueryWindow(
            startNanos = start.epochSecond * 1_000_000_000L,
            endNanos = end.epochSecond * 1_000_000_000L,
            startMillis = start.toEpochMilli(),
            endMillis = end.toEpochMilli(),
        )
    }

    private data class QueryWindow(
        val startNanos: Long,
        val endNanos: Long,
        val startMillis: Long,
        val endMillis: Long,
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LokiQueryRangeResponse(
    val status: String? = null,
    val data: LokiQueryRangeData? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LokiQueryRangeData(
    val resultType: String? = null,
    val result: List<LokiStream> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LokiStream(
    val stream: Map<String, String> = emptyMap(),
    // [timestamp_ns, line] 튜플의 리스트로 온다.
    val values: List<List<String>> = emptyList(),
)
