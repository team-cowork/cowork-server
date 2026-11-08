package com.cowork.gateway.response.filter

import com.cowork.gateway.response.body.BoundedResponseBodyTransformer
import com.cowork.gateway.response.config.ApiResponseWrapperProperties
import com.cowork.gateway.response.metrics.ApiResponseWrapperMetrics
import com.cowork.gateway.response.metrics.ApiResponseWrappingOutcome
import com.cowork.gateway.response.wrapping.ApiResponseWrappingDecision
import com.cowork.gateway.response.wrapping.ApiResponseWrappingPolicy
import org.reactivestreams.Publisher
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import team.themoment.sdk.response.CommonApiResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class ApiResponseWrapperFilter(
    private val objectMapper: ObjectMapper,
    properties: ApiResponseWrapperProperties,
    private val metrics: ApiResponseWrapperMetrics,
) : WebFilter,
    Ordered {

    private val policy = ApiResponseWrappingPolicy(properties)
    private val transformer = BoundedResponseBodyTransformer(properties.maxWrappableBytes())

    // Include authentication failures that finish before Gateway route filters run.
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (exchange.request.uri.rawPath.startsWith("/api/storage/")) return chain.filter(exchange)

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
                }.switchIfEmpty(
                    Mono.defer {
                        val bytes = emptyErrorBody()
                        if (bytes != null) updateHeadersForTransformedBody(bytes)
                        metrics.record(
                            exchange = exchange,
                            outcome = if (bytes == null) {
                                ApiResponseWrappingOutcome.BYPASS_EMPTY
                            } else {
                                ApiResponseWrappingOutcome.WRAPPED
                            },
                            sourceBytes = 0,
                            resultBytes = bytes?.size?.toLong(),
                            durationNanos = System.nanoTime() - startedAt,
                        )
                        if (bytes == null) Mono.empty() else Mono.just(bufferFactory().wrap(bytes))
                    },
                ).doOnError {
                    metrics.record(
                        exchange = exchange,
                        outcome = ApiResponseWrappingOutcome.ERROR,
                        durationNanos = System.nanoTime() - startedAt,
                    )
                }

                return super.writeWith(transformedBody)
            }

            override fun setComplete(): Mono<Void> {
                if (isCommitted) return super.setComplete()
                val bytes = emptyErrorBody() ?: return super.setComplete()
                updateHeadersForTransformedBody(bytes)
                metrics.record(
                    exchange = exchange,
                    outcome = ApiResponseWrappingOutcome.WRAPPED,
                    resultBytes = bytes.size.toLong(),
                )
                return super.writeWith(Mono.just(bufferFactory().wrap(bytes)))
            }

            private fun emptyErrorBody(): ByteArray? {
                if (exchange.request.method == HttpMethod.HEAD ||
                    policy.excludesPath(exchange.request.uri.path)
                ) {
                    return null
                }
                val status = statusCode?.value()?.let(HttpStatus::resolve) ?: return null
                if (!status.isError) return null
                val message = if (status == HttpStatus.TOO_MANY_REQUESTS) RATE_LIMITED_MESSAGE else status.reasonPhrase
                return objectMapper.writeValueAsBytes(CommonApiResponse.error(message, status))
            }

            override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> {
                metrics.record(
                    exchange = exchange,
                    outcome = ApiResponseWrappingOutcome.BYPASS_STREAMING,
                )
                return super.writeAndFlushWith(body)
            }

            private fun updateHeadersForTransformedBody(bytes: ByteArray) {
                headers.contentType = MediaType.APPLICATION_JSON
                headers.remove(HttpHeaders.TRANSFER_ENCODING)
                headers.remove(HttpHeaders.CONTENT_ENCODING)
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
        val alreadyWrapped = jsonNode.isCommonApiResponse()
        val message = if (alreadyWrapped || status.isError) {
            jsonNode?.get("message")?.takeIf { it.isString }?.asString()
                ?: jsonNode?.get("error")?.takeIf { it.isString }?.asString()
                ?: jsonNode?.takeIf { it.isString }?.asString()
                ?: bytes.toString(Charsets.UTF_8).trim().takeIf { jsonNode == null && it.isNotEmpty() }
                ?: status.reasonPhrase
        } else {
            status.reasonPhrase
        }
        val payload = if (alreadyWrapped) {
            jsonNode?.get("data")
        } else if (status.isError) {
            null
        } else {
            jsonNode
        }
        val body = CommonApiResponse(
            status = status,
            code = status.value(),
            message = message,
            data = payload?.takeUnless { it.isNull },
        )

        return PreparedResponse(
            bytes = objectMapper.writeValueAsBytes(body),
            outcome = if (alreadyWrapped) {
                ApiResponseWrappingOutcome.ALREADY_WRAPPED
            } else {
                ApiResponseWrappingOutcome.WRAPPED
            },
            wasTransformed = true,
        )
    }

    private fun JsonNode?.isCommonApiResponse(): Boolean {
        if (this == null || !isObject || !COMMON_RESPONSE_FIELDS.containsAll(propertyNames())) return false
        val declaredStatus = get("status")?.takeIf { it.isString }?.asString() ?: return false
        val code = get("code")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: return false
        if (get("message")?.isString != true) return false
        val httpStatus = HttpStatus.resolve(code) ?: return false
        // Services serialize the status as either "400 BAD_REQUEST" or "BAD_REQUEST".
        return declaredStatus == httpStatus.toString() || declaredStatus == httpStatus.name
    }

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

    companion object {
        private const val RATE_LIMITED_MESSAGE = "요청이 너무 많습니다. 잠시 후 다시 시도하십시오."
        private val COMMON_RESPONSE_FIELDS = setOf("status", "code", "message", "data")
    }
}
