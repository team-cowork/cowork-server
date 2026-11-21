package com.cowork.gateway.security.filter

import com.cowork.gateway.security.config.DevOriginProperties
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
import team.themoment.sdk.logging.logger.logger
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * 개발 계정 로그인과 교차 출처 loopback HTTP 요청의 개발 자격증명을 검사한다.
 * 자격증명을 제거한 뒤 개발 계정 로그인에만 Gateway의 검증 결과를 붙인다.
 */
@Component
@EnableConfigurationProperties(DevOriginProperties::class)
class DevOriginCredentialFilter(
    properties: DevOriginProperties,
    private val globalCorsProperties: GlobalCorsProperties,
) : WebFilter,
    Ordered {

    private val expected = properties.credential.toByteArray(Charsets.UTF_8)

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val origin = request.headers.origin
        val loopbackOrigin = origin?.let { runCatching { URI(it) }.getOrNull() }?.takeIf(::isLoopbackHttp)

        val devSignIn =
            request.method == HttpMethod.POST && request.path.pathWithinApplication().value() == DEV_SIGNIN_PATH
        val requiresLoopbackCredential = loopbackOrigin != null && !isSameOrigin(loopbackOrigin, request.uri)
        val queryCredential = request.queryParams[CREDENTIAL_QUERY]
        val provided = if (devSignIn) {
            request.headers[CREDENTIAL_HEADER]?.singleOrNull()?.takeIf { queryCredential == null }
        } else {
            queryCredential?.singleOrNull()
        }
        if (devSignIn || (request.method != HttpMethod.OPTIONS && requiresLoopbackCredential)) {
            if (provided == null || !MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected)) {
                logger().warn("Reject request without a valid developer credential")
                if (origin != null) exposeRejection(exchange, origin)
                exchange.response.statusCode = HttpStatus.FORBIDDEN
                return exchange.response.setComplete()
            }
        }

        if (request.uri.rawQuery == null &&
            !request.headers.containsHeader(CREDENTIAL_HEADER) &&
            !request.headers.containsHeader(VERIFIED_HEADER)
        ) {
            return chain.filter(exchange)
        }
        val builder = request.mutate()
        builder.headers {
            it.remove(CREDENTIAL_HEADER)
            it.remove(VERIFIED_HEADER)
            if (devSignIn) it.set(VERIFIED_HEADER, "true")
        }
        val rawQuery = request.uri.rawQuery
        if (rawQuery != null) {
            val params = rawQuery.split('&')
            val kept = params.filterNot { paramName(it) == CREDENTIAL_QUERY }
            if (kept.size != params.size) {
                val base = request.uri.toString().substringBefore('?')
                builder.uri(URI.create(if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"))
            }
        }
        return chain.filter(exchange.mutate().request(builder.build()).build())
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
        const val DEV_SIGNIN_PATH = "/api/authorization/auth/dev-signin"
        private const val CREDENTIAL_HEADER = "X-Dev-Credential"
        private const val VERIFIED_HEADER = "X-Cowork-Dev-Signin-Verified"
        const val CREDENTIAL_QUERY = "dev_credential"
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")
    }
}
