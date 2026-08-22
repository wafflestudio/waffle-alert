package com.wafflestudio.alert.source.k8s

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.io.File
import java.net.http.HttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** 쿠버네티스 API 서버 전용 [RestClient]. watch용과 일반 요청용의 타임아웃이 달라 둘로 나눈다. */
@Configuration
@ConditionalOnK8sWatch
class K8sClientConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /** watch 전용. **read timeout을 걸면 안 된다** - 이벤트가 없으면 몇 분이고 조용한 게 정상이다. */
    @Bean
    fun k8sRestClient(props: K8sProperties): RestClient {
        log.info(
            "k8s watch 클라이언트 준비: apiUrl={}, token={}, ca={}",
            props.apiUrl,
            if (File(props.tokenPath).exists()) "마운트됨" else "없음(로컬 모드)",
            if (File(props.caPath).exists()) "마운트됨" else "없음(기본 신뢰저장소)",
        )
        return buildClient(props, JdkClientHttpRequestFactory(sharedHttpClient(props)))
    }

    /** 일반 요청용. 응답이 안 오는 요청 하나가 워처 스레드를 영원히 붙잡지 않도록 read timeout을 건다. */
    @Bean
    fun k8sApiRestClient(props: K8sProperties): RestClient =
        buildClient(
            props,
            JdkClientHttpRequestFactory(sharedHttpClient(props)).apply { setReadTimeout(props.apiReadTimeout) },
        )

    private fun sharedHttpClient(props: K8sProperties): HttpClient =
        HttpClient
            .newBuilder()
            // 기본값(HTTP/2)은 평문 http://에서 h2c 업그레이드를 시도하는데 `kubectl proxy`가 이를 500으로 처리한다.
            // HTTP/2는 죽은 커넥션을 늦게 감지해 watch가 조용히 멈추는 문제도 알려져 있다.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(props.connectTimeout)
            .apply { File(props.caPath).takeIf { it.exists() }?.let { sslContext(trustStoreFrom(it)) } }
            .build()

    private fun buildClient(
        props: K8sProperties,
        requestFactory: JdkClientHttpRequestFactory,
    ): RestClient {
        val tokenFile = File(props.tokenPath)

        return RestClient
            .builder()
            .baseUrl(props.apiUrl)
            .requestFactory(requestFactory)
            // SA 토큰은 만료되고 kubelet이 파일을 갈아끼우므로 요청마다 다시 읽는다.
            .requestInitializer { request ->
                readToken(tokenFile)?.let { request.headers.setBearerAuth(it) }
            }.build()
    }

    private fun readToken(tokenFile: File): String? =
        runCatching {
            tokenFile
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.ifBlank { null }
        }.getOrElse {
            log.error("ServiceAccount 토큰 파일을 읽지 못했다: {}", tokenFile, it)
            null
        }

    /** API 서버 인증서는 클러스터가 자체 발급한 것이라 JDK 기본 신뢰저장소로는 검증되지 않는다. */
    private fun trustStoreFrom(caFile: File): SSLContext {
        val certificates = caFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        val keyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEachIndexed { index, certificate -> setCertificateEntry("k8s-ca-$index", certificate) }
            }
        val trustManagers =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }.trustManagers

        return SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }
    }
}
