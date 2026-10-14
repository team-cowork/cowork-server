package com.cowork.gateway.security.filter

import com.cowork.gateway.security.config.DevOriginProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import java.net.URI
import java.security.MessageDigest

/**
 * loopback HTTP origin 요청은 `dev_credential` query가 약정한 값과 같아야 통과한다.
 * 브라우저 WebSocket은 커스텀 헤더를 보낼 수 없어 query로 통일했고, 저장소 서명 검증을 깨지 않도록 항상 제거한다.
 */
@Component
@EnableConfigurationProperties(DevOriginProperties::class)
class DevOriginCredentialFilter(private val properties: DevOriginProperties) :
    WebFilter,
    Ordered {

    private val log = LoggerFactory.getLogger(DevOriginCredentialFilter::class.java)
    private val expected = properties.credential.toByteArray(Charsets.UTF_8)

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val provided = request.queryParams.getFirst(CREDENTIAL_QUERY)

        if (properties.enforce && request.method != HttpMethod.OPTIONS && isLoopbackHttp(request.headers.origin)) {
            if (provided == null || !MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected)) {
                log.warn("Reject loopback origin request without a valid developer credential")
                exchange.response.statusCode = HttpStatus.FORBIDDEN
                return exchange.response.setComplete()
            }
        }

        if (!request.queryParams.containsKey(CREDENTIAL_QUERY)) {
            return chain.filter(exchange)
        }
        val stripped = UriComponentsBuilder.fromUri(request.uri)
            .replaceQueryParam(CREDENTIAL_QUERY)
            .build(true)
            .toUri()
        return chain.filter(exchange.mutate().request(request.mutate().uri(stripped).build()).build())
    }

    private fun isLoopbackHttp(origin: String?): Boolean {
        val uri = origin?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return uri.scheme == "http" && uri.host in LOOPBACK_HOSTS
    }

    companion object {
        const val CREDENTIAL_QUERY = "dev_credential"
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")
    }
}
