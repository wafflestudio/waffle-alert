package com.wafflestudio.alert.source.k8s

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * watch 스트림 **한 번의 접속**을 담당한다. 스레드도 재시도도 [K8sWatchRunner] 몫이다.
 */
@Component
@ConditionalOnK8sWatch
class K8sWatchClient(
    @Qualifier("k8sRestClient") private val k8sRestClient: RestClient,
    private val jsonMapper: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * **오래 블로킹된다**(몇 분~수십 분). 전용 스레드에서만 호출할 것.
     * 정상 리턴은 서버가 커넥션을 끊었다는 뜻이지 오류가 아니다 - 호출자가 다시 붙으면 된다.
     *
     * @param isActive false가 되면 남은 줄을 소비하지 않고 빠져나온다(종료용).
     */
    fun stream(
        path: String,
        isActive: () -> Boolean,
        onEvent: (JsonNode) -> Unit,
    ) {
        k8sRestClient
            .get()
            .uri(path)
            // watch 응답은 끝나지 않으므로 retrieve().body()로 받으면 영원히 블록된다.
            // exchange()만 body를 InputStream 상태로 넘겨준다. 상태 코드 검사도 직접 해야 한다.
            .exchange { _, response ->
                val status = response.statusCode
                when {
                    status.value() == 401 || status.value() == 403 ->
                        throw K8sAuthException("watch 권한이 없다 (status=$status, path=$path). ClusterRole/Binding 확인 필요")
                    !status.is2xxSuccessful ->
                        throw K8sWatchException("watch 실패 (status=$status, path=$path)", status.value())
                }

                response.body.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        // onEvent 호출 전에 확인한다. 종료 신호가 왔는데 한 건 더 처리하면 안 된다.
                        if (!isActive()) break
                        if (line.isBlank()) continue

                        val event = parseOrNull(line) ?: continue
                        onEvent(event)
                    }
                }
            }
    }

    /** 깨진 줄 하나 때문에 커넥션을 끊고 다시 붙는 것보다 그 줄을 버리는 편이 싸다. */
    private fun parseOrNull(line: String): JsonNode? =
        runCatching { jsonMapper.readTree(line) }
            .getOrElse {
                log.warn("watch 줄을 파싱하지 못해 건너뛴다: {}", line.take(MAX_LOGGED_LINE_LENGTH))
                null
            }

    private companion object {
        const val MAX_LOGGED_LINE_LENGTH = 200
    }
}
