package com.wafflestudio.alert.source.k8s

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 워처 스레드의 생애를 관리한다. 리소스 종류마다 스레드를 하나씩 쓴다 - watch는 `readLine()` 에서
 * 블로킹되므로 스레드가 통째로 대기하지만, 최대 3개라 WebFlux를 들여오는 것보다 싸다.
 */
@Component
@ConditionalOnK8sWatch
class K8sWatchRunner(
    private val watchClient: K8sWatchClient,
    private val handlers: List<K8sWatchHandler>,
    private val props: K8sProperties,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var running = false
    private var executor: ExecutorService? = null

    override fun isRunning(): Boolean = running

    override fun start() {
        val active = handlers.filter { props.isEnabled(it.kind) }
        if (active.isEmpty()) {
            log.warn("활성화된 k8s 워처가 없다. alert.k8s.pod/job/node 설정을 확인할 것")
            return
        }

        running = true
        // readLine()으로 블로킹된 스레드는 인터럽트에 즉시 반응하지 못할 수 있다. 데몬이면 JVM 종료는 막지 않는다.
        executor =
            Executors.newFixedThreadPool(active.size) { runnable ->
                Thread(runnable, "k8s-watch").apply { isDaemon = true }
            }
        active.forEach { handler -> executor?.submit { watchForever(handler) } }
        log.info("k8s 워처 기동: {}", active.map { it.kind })
    }

    override fun stop() {
        // 먼저 running을 내려야 stream() 안의 isActive가 false를 보고 스스로 빠져나온다.
        running = false
        executor?.shutdownNow()
        executor = null
        log.info("k8s 워처 정지")
    }

    /** [stop] 이 불릴 때까지 리턴하지 않는다. */
    private fun watchForever(handler: K8sWatchHandler) {
        var backoff = props.minBackoff

        while (running) {
            try {
                watchClient.stream(handler.path(), ::isRunning, handler::onEvent)
                // 정상 종료(서버가 끊음). 서버가 200을 주고 곧바로 끊는 경우 지연 없는 재접속이 CPU를 태우므로
                // 최소 간격은 둔다. resourceVersion 커서로 이어붙어 이 공백 동안의 이벤트는 놓치지 않는다.
                backoff = props.minBackoff
                if (!sleepQuietly(props.minBackoff.toMillis())) return
            } catch (e: K8sAuthException) {
                // ClusterRole은 다른 레포에서 배포되므로 앱이 권한보다 먼저 뜰 수 있다. 포기하면 권한이 붙어도
                // 파드를 재시작하기 전까지 감시가 빠진 채로 남으므로, 최대 간격으로 계속 두드려 스스로 복구시킨다.
                log.error("{} watch 권한 없음 - ClusterRole 확인 필요. {} 후 재시도", handler.kind, props.maxBackoff, e)
                if (!sleepQuietly(props.maxBackoff.toMillis())) return
                backoff = props.maxBackoff
            } catch (e: K8sWatchException) {
                if (e.status == HTTP_GONE) handler.resetResourceVersion()
                log.warn("{} watch 실패, {} 후 재시도: {}", handler.kind, backoff, e.message)
                if (!sleepQuietly(backoff.toMillis())) return
                backoff = nextBackoff(backoff.toMillis())
            } catch (e: Exception) {
                // 접속 실패든 핸들러 예외든, 어떤 것도 이 스레드를 죽여서는 안 된다.
                log.warn("{} watch 실패, {} 후 재시도", handler.kind, backoff, e)
                if (!sleepQuietly(backoff.toMillis())) return
                backoff = nextBackoff(backoff.toMillis())
            }
        }
    }

    /** 실패가 이어지면 재시도 간격을 2배씩 늘린다. */
    private fun nextBackoff(currentMillis: Long) =
        java.time.Duration.ofMillis((currentMillis * 2).coerceAtMost(props.maxBackoff.toMillis()))

    /** @return 계속 진행해도 되면 true, 종료 요청을 받았으면 false */
    private fun sleepQuietly(millis: Long): Boolean =
        try {
            Thread.sleep(millis)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private companion object {
        const val HTTP_GONE = 410
    }
}
