package com.cowork.gateway.security.filter

import com.cowork.gateway.security.config.DevOriginProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.config.GlobalCorsProperties
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * loopback HTTP origin 요청은 `dev_credential` query가 약정한 값과 같아야 통과한다.
 * 브라우저 WebSocket은 커스텀 헤더를 보낼 수 없어 query로 통일했고, 저장소 서명 검증을 깨지 않도록 원문에서 항상 제거한다.
 */
@Component
@EnableConfigurationProperties(DevOriginProperties::class)
class DevOriginCredentialFilter(
    properties: DevOriginProperties,
    private val globalCorsProperties: GlobalCorsProperties,
) : WebFilter,
    Ordered {

    private val log = LoggerFactory.getLogger(DevOriginCredentialFilter::class.java)
    private val expected = properties.credential.toByteArray(Charsets.UTF_8)

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val origin = request.headers.origin
        val loopbackOrigin = origin?.let { runCatching { URI(it) }.getOrNull() }?.takeIf(::isLoopbackHttp)

        if (request.method != HttpMethod.OPTIONS && loopbackOrigin != null &&
            !isSameOrigin(loopbackOrigin, request.uri)
        ) {
            val provided = request.queryParams.getFirst(CREDENTIAL_QUERY)
            if (provided == null || !MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected)) {
                log.warn("Reject loopback origin request without a valid developer credential")
                exposeRejection(exchange, origin)
                exchange.response.statusCode = HttpStatus.FORBIDDEN
                return exchange.response.setComplete()
            }
        }

        val rawQuery = request.uri.rawQuery ?: return chain.filter(exchange)
        val params = rawQuery.split('&')
        val kept = params.filterNot { paramName(it) == CREDENTIAL_QUERY }
        if (kept.size == params.size) {
            return chain.filter(exchange)
        }
        val base = request.uri.toString().substringBefore('?')
        val stripped = URI.create(if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}")
        return chain.filter(exchange.mutate().request(request.mutate().uri(stripped).build()).build())
    }

    private fun isLoopbackHttp(origin: URI): Boolean = origin.scheme == "http" && origin.host in LOOPBACK_HOSTS

    private fun isSameOrigin(origin: URI, target: URI): Boolean =
        origin.scheme == target.scheme && origin.host == target.host && origin.effectivePort() == target.effectivePort()

    private fun URI.effectivePort(): Int = if (port != -1) {
        port
    } else if (scheme == "https") {
        443
    } else {
        80
    }

    private fun paramName(param: String): String {
        val raw = param.substringBefore('=')
        return runCatching { URLDecoder.decode(raw, Charsets.UTF_8) }.getOrDefault(raw)
    }

    // 전역 CORS는 handler mapping 단계라 이 응답에 붙지 않는다. 허용 origin이면 브라우저가 403을 그대로 읽게 한다.
    private fun exposeRejection(exchange: ServerWebExchange, origin: String) {
        if (globalCorsProperties.corsConfigurations.values.none { it.checkOrigin(origin) != null }) return
        exchange.response.headers.apply {
            set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
            set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
            add(HttpHeaders.VARY, HttpHeaders.ORIGIN)
        }
    }

    companion object {
        const val CREDENTIAL_QUERY = "dev_credential"
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")
    }
}
