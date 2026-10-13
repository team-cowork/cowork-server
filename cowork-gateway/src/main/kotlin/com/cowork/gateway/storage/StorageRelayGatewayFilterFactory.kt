package com.cowork.gateway.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.PRESERVE_HOST_HEADER_ATTRIBUTE
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI

@Component
class StorageRelayGatewayFilterFactory(
    @param:Value("\${PUBLIC_API_BASE_URL}") publicApiBaseUrl: String,
    @param:Value("\${S3_BUCKET}") bucket: String,
) : AbstractGatewayFilterFactory<StorageRelayGatewayFilterFactory.Config>(Config::class.java) {
    class Config

    private val policy = StorageRelayPolicy(bucket)
    private val endpoint = URI.create(publicApiBaseUrl).also {
        require(
            it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() &&
                it.path in setOf("", "/") && it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null,
        ) { "PUBLIC_API_BASE_URL must be an HTTP(S) origin" }
    }

    override fun apply(config: Config): GatewayFilter = GatewayFilter { exchange, chain ->
        val request = exchange.request
        if (!policy.allows(request.method, request.uri.rawPath, request.queryParams.keys)) {
            exchange.response.statusCode = HttpStatus.FORBIDDEN
            return@GatewayFilter exchange.response.setComplete()
        }
        if (request.headers.contentLength > StorageRelayPolicy.MAX_UPLOAD_BYTES) {
            exchange.response.statusCode = HttpStatus.CONTENT_TOO_LARGE
            return@GatewayFilter exchange.response.setComplete()
        }

        // 서명은 기존 API origin 기준이다. Gateway 접두사만 제거하고 Host와 원래 query를 복원한다.
        exchange.attributes[PRESERVE_HOST_HEADER_ATTRIBUTE] = true
        val routedRequest = request.mutate().headers { headers ->
            headers.headerNames().toList().filter { it.lowercase() !in forwardedHeaders }.forEach(headers::remove)
            val defaultPort = if (endpoint.scheme == "https") 443 else 80
            val port = endpoint.port.takeIf { it != -1 && it != defaultPort }?.let { ":$it" }.orEmpty()
            headers.set(HttpHeaders.HOST, "${endpoint.host}$port")
        }.build()
        val boundedRequest = object : ServerHttpRequestDecorator(routedRequest) {
            override fun getBody(): Flux<DataBuffer> = Flux.defer {
                var bytes = 0L
                super.getBody().handle<DataBuffer> { buffer, sink ->
                    bytes += buffer.readableByteCount()
                    if (bytes > StorageRelayPolicy.MAX_UPLOAD_BYTES) {
                        DataBufferUtils.release(buffer)
                        sink.error(ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE))
                    } else {
                        sink.next(buffer)
                    }
                }.doOnDiscard(DataBuffer::class.java, DataBufferUtils::release)
            }
        }
        exchange.response.beforeCommit {
            exchange.response.headers.cacheControl = "private, no-store"
            exchange.response.headers.set("X-Content-Type-Options", "nosniff")
            // 업로드 파일이 API origin에서 능동 콘텐츠로 실행되지 않도록 제한한다.
            exchange.response.headers.set("Content-Security-Policy", "sandbox; default-src 'none'")
            Mono.empty()
        }
        chain.filter(exchange.mutate().request(boundedRequest).build())
    }

    companion object {
        private val forwardedHeaders = setOf(
            "content-type",
            "content-length",
            "range",
            "if-range",
            "if-none-match",
            "if-modified-since",
            "accept",
        )
    }
}
