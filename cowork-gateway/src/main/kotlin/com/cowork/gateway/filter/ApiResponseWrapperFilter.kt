package com.cowork.gateway.filter

import com.cowork.gateway.response.CommonApiResponse
import org.reactivestreams.Publisher
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class ApiResponseWrapperFilter(
    private val objectMapper: ObjectMapper,
    properties: ApiResponseWrapperProperties,
    private val metrics: ApiResponseWrapperMetrics,
) : GlobalFilter,
    Ordered {

    private val policy = ApiResponseWrappingPolicy(properties)
    private val transformer = BoundedResponseBodyTransformer(properties.maxWrappableBytes())

    // NettyWriteResponseFilter 직전에 실행되어야 응답 바디를 가로챌 수 있음
    override fun getOrder(): Int = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val decoratedResponse = object : ServerHttpResponseDecorator(exchange.response) {
            override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                val decision = policy.decide(
                    path = exchange.request.uri.path,
                    method = exchange.request.method,
                    statusCode = statusCode,
                    headers = headers,
                )
                if (decision != ApiResponseWrappingDecision.WRAP_BOUNDED) {
                    metrics.record(
                        exchange = exchange,
                        outcome = decision.toOutcome(),
                        sourceBytes = headers.contentLength.takeIf { it >= 0 },
                    )
                    return super.writeWith(body)
                }

                val startedAt = System.nanoTime()
                val transformedBody = transformer.transform(
                    body = body,
                    onThresholdExceeded = { totalBytes ->
                        metrics.record(
                            exchange = exchange,
                            outcome = ApiResponseWrappingOutcome.BYPASS_THRESHOLD,
                            sourceBytes = totalBytes.toLong(),
                            durationNanos = System.nanoTime() - startedAt,
                        )
                    },
                    onEmpty = {
                        metrics.record(
                            exchange = exchange,
                            outcome = ApiResponseWrappingOutcome.BYPASS_EMPTY,
                            durationNanos = System.nanoTime() - startedAt,
                        )
                    },
                ) { bytes ->
                    val httpStatus = statusCode?.let {
                        runCatching { HttpStatus.valueOf(it.value()) }.getOrNull()
                    } ?: HttpStatus.OK
                    val response = prepareResponse(bytes, httpStatus)
                    if (response.wasTransformed) {
                        updateHeadersForTransformedBody(response.bytes)
                    }
                    metrics.record(
                        exchange = exchange,
                        outcome = response.outcome,
                        sourceBytes = bytes.size.toLong(),
                        resultBytes = response.bytes.size.toLong(),
                        durationNanos = System.nanoTime() - startedAt,
                    )
                    bufferFactory().wrap(response.bytes)
                }.doOnError {
                    metrics.record(
                        exchange = exchange,
                        outcome = ApiResponseWrappingOutcome.ERROR,
                        durationNanos = System.nanoTime() - startedAt,
                    )
                }

                return super.writeWith(transformedBody)
            }

            override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> {
                metrics.record(
                    exchange = exchange,
                    outcome = ApiResponseWrappingOutcome.BYPASS_STREAMING,
                )
                return super.writeAndFlushWith(body)
            }

            private fun updateHeadersForTransformedBody(bytes: ByteArray) {
                headers.remove(HttpHeaders.TRANSFER_ENCODING)
                headers.remove(HttpHeaders.ETAG)
                headers.remove("Content-MD5")
                headers.remove("Digest")
                headers.contentLength = bytes.size.toLong()
            }
        }

        return chain.filter(exchange.mutate().response(decoratedResponse).build())
    }

    private fun prepareResponse(bytes: ByteArray, status: HttpStatus): PreparedResponse {
        val jsonNode = runCatching { objectMapper.readTree(bytes) }.getOrNull()
        if (jsonNode.isCommonApiResponse()) {
            return PreparedResponse(
                bytes = bytes,
                outcome = ApiResponseWrappingOutcome.ALREADY_WRAPPED,
                wasTransformed = false,
            )
        }

        val body = if (status.isError) {
            val message = jsonNode?.get("message")?.asString() ?: status.reasonPhrase
            CommonApiResponse.error(status, message)
        } else {
            val data = jsonNode?.let { objectMapper.treeToValue(it, Any::class.java) }
            CommonApiResponse.success(data)
        }

        return PreparedResponse(
            bytes = objectMapper.writeValueAsBytes(body),
            outcome = ApiResponseWrappingOutcome.WRAPPED,
            wasTransformed = true,
        )
    }

    private fun JsonNode?.isCommonApiResponse(): Boolean = this != null &&
        has("code") &&
        has("status") &&
        has("message")

    private fun ApiResponseWrappingDecision.toOutcome(): ApiResponseWrappingOutcome = when (this) {
        ApiResponseWrappingDecision.BYPASS_PATH -> ApiResponseWrappingOutcome.BYPASS_PATH
        ApiResponseWrappingDecision.BYPASS_CONTENT_TYPE -> ApiResponseWrappingOutcome.BYPASS_CONTENT_TYPE
        ApiResponseWrappingDecision.BYPASS_KNOWN_LARGE -> ApiResponseWrappingOutcome.BYPASS_KNOWN_LARGE
        ApiResponseWrappingDecision.BYPASS_EMPTY -> ApiResponseWrappingOutcome.BYPASS_EMPTY
        ApiResponseWrappingDecision.BYPASS_STREAMING -> ApiResponseWrappingOutcome.BYPASS_STREAMING
        ApiResponseWrappingDecision.WRAP_BOUNDED -> error("wrapping decision must not be recorded as a bypass")
    }

    private data class PreparedResponse(
        val bytes: ByteArray,
        val outcome: ApiResponseWrappingOutcome,
        val wasTransformed: Boolean,
    )
}
