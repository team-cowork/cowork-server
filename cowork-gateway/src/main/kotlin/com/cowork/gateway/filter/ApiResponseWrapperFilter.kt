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

    // 이 크기를 초과하는 응답은 버퍼링 OOM 위험이 있으므로 래핑 스킵 (1MB)
    private val maxWrappableBytes = 1024 * 1024L

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
                // Content-Length가 없거나(Chunked 등) 임계값을 초과하면 래핑 스킵 (OOM 방지)
                // contentLength == -1: Content-Length 헤더 없음 → 전체 크기 불명 → 버퍼링 불가
                val contentLength = headers.contentLength
                if (contentLength < 0 || contentLength > maxWrappableBytes) {
                    return super.writeWith(body)
                }

                val modifiedBody: Mono<DataBuffer> = Flux.from(body)
                    .collectList()
                    .flatMap { buffers ->
                        // 미리 전체 크기를 계산해 단일 배열에 복사 (O(n) 보장)
                        val totalSize = buffers.sumOf { it.readableByteCount() }
                        val bytes = ByteArray(totalSize)
                        var offset = 0
                        buffers.forEach { buf ->
                            val len = buf.readableByteCount()
                            buf.read(bytes, offset, len)
                            DataBufferUtils.release(buf)
                            offset += len
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
