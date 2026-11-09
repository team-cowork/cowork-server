package com.cowork.gateway.response.filter

import com.cowork.gateway.response.config.ApiResponseWrapperProperties
import com.cowork.gateway.response.metrics.ApiResponseWrapperMetrics
import com.cowork.gateway.response.metrics.ApiResponseWrappingOutcome
import com.cowork.gateway.response.wrapping.ApiResponseWrappingDecision
import com.cowork.gateway.response.wrapping.ApiResponseWrappingPolicy
import org.reactivestreams.Publisher
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import team.themoment.sdk.response.CommonApiResponse
import tools.jackson.databind.ObjectMapper

@Component
class ApiResponseWrapperFilter(
    private val objectMapper: ObjectMapper,
    properties: ApiResponseWrapperProperties,
    private val metrics: ApiResponseWrapperMetrics,
) : WebFilter,
    Ordered {

    private val policy = ApiResponseWrappingPolicy(properties)
    private val maxErrorBodyBytes = properties.maxErrorBodyBytes()

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
                if (decision != ApiResponseWrappingDecision.WRAP) {
                    metrics.record(
                        exchange,
                        decision.toOutcome(),
                        sourceBytes = headers.contentLength.takeIf {
                            it >= 0
                        },
                    )
                    return super.writeWith(body)
                }
                val status = statusCode?.value()?.let(HttpStatus::resolve) ?: HttpStatus.OK
                return super.writeWith(if (status.isError) wrapError(body, status) else wrapSuccess(body, status))
            }

            private fun wrapSuccess(body: Publisher<out DataBuffer>, status: HttpStatus): Flux<DataBuffer> {
                val startedAt = System.nanoTime()
                var sourceBytes = 0L
                var resultBytes = 0L
                val metadata = objectMapper.writeValueAsString(
                    mapOf("status" to status.toString(), "code" to status.value(), "message" to status.reasonPhrase),
                )
                val prefix = (metadata.dropLast(1) + ",\"data\":").toByteArray(Charsets.UTF_8)
                return Flux.from(body)
                    .filter { buffer ->
                        if (buffer.readableByteCount() == 0) {
                            false
                        } else {
                            sourceBytes += buffer.readableByteCount()
                            true
                        }
                    }
                    .switchOnFirst { signal, source ->
                        if (!signal.hasValue()) {
                            source
                        } else {
                            updateHeaders()
                            Flux.concat(
                                Mono.fromSupplier { bufferFactory().wrap(prefix) },
                                source,
                                Mono.fromSupplier { bufferFactory().wrap(byteArrayOf('}'.code.toByte())) },
                            )
                        }
                    }
                    .doOnNext { resultBytes += it.readableByteCount() }
                    .doOnDiscard(DataBuffer::class.java, DataBufferUtils::release)
                    .doFinally { signal ->
                        val completed = if (sourceBytes == 0L) {
                            ApiResponseWrappingOutcome.BYPASS_EMPTY
                        } else {
                            ApiResponseWrappingOutcome.WRAPPED
                        }
                        metrics.record(
                            exchange,
                            terminalOutcome(signal, completed),
                            sourceBytes = sourceBytes,
                            resultBytes = resultBytes,
                            durationNanos = System.nanoTime() - startedAt,
                        )
                    }
            }

            private fun wrapError(body: Publisher<out DataBuffer>, status: HttpStatus): Mono<DataBuffer> {
                val startedAt = System.nanoTime()
                var sourceBytes = 0L
                var resultBytes = 0L
                var outcome = ApiResponseWrappingOutcome.WRAPPED
                return DataBufferUtils.join(
                    Flux.from(body).doOnNext { sourceBytes += it.readableByteCount() },
                    maxErrorBodyBytes,
                )
                    .map { buffer ->
                        try {
                            val bytes = ByteArray(buffer.readableByteCount())
                            buffer.read(bytes)
                            errorBody(bytes, status)
                        } finally {
                            DataBufferUtils.release(buffer)
                        }
                    }
                    .switchIfEmpty(Mono.fromSupplier { errorBody(ByteArray(0), status) })
                    .onErrorResume(DataBufferLimitException::class.java) {
                        outcome = ApiResponseWrappingOutcome.ERROR_BODY_TOO_LARGE
                        Mono.just(errorBody(ByteArray(0), status))
                    }
                    .map { bytes ->
                        updateHeaders(bytes.size.toLong())
                        resultBytes = bytes.size.toLong()
                        bufferFactory().wrap(bytes)
                    }
                    .doOnDiscard(DataBuffer::class.java, DataBufferUtils::release)
                    .doFinally { signal ->
                        metrics.record(
                            exchange,
                            terminalOutcome(signal, outcome),
                            // An oversized error records only the bytes received before cancellation.
                            sourceBytes = sourceBytes,
                            resultBytes = resultBytes,
                            durationNanos = System.nanoTime() - startedAt,
                        )
                    }
            }

            override fun setComplete(): Mono<Void> {
                if (isCommitted || exchange.request.method == HttpMethod.HEAD ||
                    policy.excludesPath(exchange.request.uri.path)
                ) {
                    return super.setComplete()
                }
                val status = statusCode?.value()?.let(HttpStatus::resolve) ?: return super.setComplete()
                if (!status.isError) return super.setComplete()
                val bytes = errorBody(ByteArray(0), status)
                updateHeaders(bytes.size.toLong())
                metrics.record(
                    exchange,
                    ApiResponseWrappingOutcome.WRAPPED,
                    sourceBytes = 0,
                    resultBytes = bytes.size.toLong(),
                )
                return super.writeWith(Mono.just(bufferFactory().wrap(bytes)))
            }

            override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> {
                metrics.record(exchange, ApiResponseWrappingOutcome.BYPASS_STREAMING)
                return super.writeAndFlushWith(body)
            }

            private fun updateHeaders(contentLength: Long? = null) {
                headers.contentType = MediaType.APPLICATION_JSON
                headers.remove(HttpHeaders.CONTENT_LENGTH)
                headers.remove(HttpHeaders.TRANSFER_ENCODING)
                headers.remove(HttpHeaders.CONTENT_ENCODING)
                headers.remove(HttpHeaders.ETAG)
                headers.remove("Content-MD5")
                headers.remove("Digest")
                if (contentLength != null) headers.contentLength = contentLength
            }
        }
        return chain.filter(exchange.mutate().response(decoratedResponse).build())
    }

    private fun errorBody(bytes: ByteArray, status: HttpStatus): ByteArray {
        val json = runCatching { objectMapper.readTree(bytes) }.getOrNull()
        val message = json?.get("message")?.takeIf { it.isString }?.asString()
            ?: json?.get("error")?.takeIf { it.isString }?.asString()
            ?: json?.takeIf { it.isString }?.asString()
            ?: bytes.toString(Charsets.UTF_8).trim().takeIf { json == null && it.isNotEmpty() }
            ?: if (status == HttpStatus.TOO_MANY_REQUESTS) RATE_LIMITED_MESSAGE else status.reasonPhrase
        return objectMapper.writeValueAsBytes(
            CommonApiResponse(status, status.value(), message, json?.get("data")?.takeUnless { it.isNull }),
        )
    }

    private fun terminalOutcome(signal: SignalType, completed: ApiResponseWrappingOutcome): ApiResponseWrappingOutcome =
        when (signal) {
            SignalType.ON_COMPLETE -> completed
            SignalType.CANCEL -> ApiResponseWrappingOutcome.CANCELLED
            else -> ApiResponseWrappingOutcome.ERROR
        }

    private fun ApiResponseWrappingDecision.toOutcome(): ApiResponseWrappingOutcome = when (this) {
        ApiResponseWrappingDecision.BYPASS_PATH -> ApiResponseWrappingOutcome.BYPASS_PATH
        ApiResponseWrappingDecision.BYPASS_CONTENT_TYPE -> ApiResponseWrappingOutcome.BYPASS_CONTENT_TYPE
        ApiResponseWrappingDecision.BYPASS_EMPTY -> ApiResponseWrappingOutcome.BYPASS_EMPTY
        ApiResponseWrappingDecision.BYPASS_STREAMING -> ApiResponseWrappingOutcome.BYPASS_STREAMING
        ApiResponseWrappingDecision.WRAP -> error("wrapping decision must not be recorded as a bypass")
    }

    companion object {
        private const val RATE_LIMITED_MESSAGE = "요청이 너무 많습니다. 잠시 후 다시 시도하십시오."
    }
}
