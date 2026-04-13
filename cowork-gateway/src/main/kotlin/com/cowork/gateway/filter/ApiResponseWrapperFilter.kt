package com.cowork.gateway.filter

import com.cowork.gateway.response.CommonApiResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.reactivestreams.Publisher
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
class ApiResponseWrapperFilter(
    private val objectMapper: ObjectMapper,
) : GlobalFilter, Ordered {

    // 래핑하지 않을 경로 (actuator, fallback 등)
    private val skipPatterns = listOf(
        "/actuator/**",
        "/fallback",
    )
    private val matcher = AntPathMatcher()

    // NettyWriteResponseFilter 직전에 실행되어야 응답 바디를 가로챌 수 있음
    override fun getOrder(): Int = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        if (skipPatterns.any { matcher.match(it, path) }) {
            return chain.filter(exchange)
        }

        val decoratedResponse = object : ServerHttpResponseDecorator(exchange.response) {
            override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                val contentType = headers.contentType
                // JSON 응답이 아니면 그대로 통과
                if (contentType == null || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                    return super.writeWith(body)
                }

                val modifiedBody: Mono<DataBuffer> = Flux.from(body)
                    .collectList()
                    .flatMap { buffers ->
                        // 여러 청크를 하나로 합침
                        val bytes = buffers.fold(byteArrayOf()) { acc, buf ->
                            val chunk = ByteArray(buf.readableByteCount())
                            buf.read(chunk)
                            DataBufferUtils.release(buf)
                            acc + chunk
                        }

                        val httpStatus = statusCode?.let {
                            runCatching { HttpStatus.valueOf(it.value()) }.getOrNull()
                        } ?: HttpStatus.OK

                        val wrapped = buildWrappedResponse(bytes, httpStatus)
                        val wrappedBytes = objectMapper.writeValueAsBytes(wrapped)

                        // Content-Length 갱신
                        headers.contentLength = wrappedBytes.size.toLong()

                        Mono.just(bufferFactory().wrap(wrappedBytes))
                    }

                return super.writeWith(modifiedBody)
            }

            // chunked transfer-encoding 응답(writeAndFlushWith)도 처리
            override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> {
                return writeWith(Flux.from(body).flatMapSequential { it })
            }
        }

        return chain.filter(exchange.mutate().response(decoratedResponse).build())
    }

    private fun buildWrappedResponse(bytes: ByteArray, status: HttpStatus): CommonApiResponse<*> {
        // 빈 바디 (204 No Content 등)
        if (bytes.isEmpty()) {
            return CommonApiResponse.noContent()
        }

        // 에러 응답 (4xx, 5xx)
        if (status.isError) {
            val message = runCatching {
                val node = objectMapper.readTree(bytes)
                node.get("message")?.asText() ?: status.reasonPhrase
            }.getOrDefault(status.reasonPhrase)

            return CommonApiResponse.error(status, message)
        }

        // 정상 응답 — 이미 CommonApiResponse 형태면 그대로 통과
        val jsonNode = runCatching { objectMapper.readTree(bytes) }.getOrNull()
        if (jsonNode != null && jsonNode.has("code") && jsonNode.has("status") && jsonNode.has("message")) {
            return objectMapper.treeToValue(jsonNode, CommonApiResponse::class.java)
        }

        // 그 외 정상 응답은 data 필드에 넣어 래핑
        val data = runCatching { objectMapper.readValue(bytes, Any::class.java) }.getOrNull()
        return CommonApiResponse.success(data)
    }
}
