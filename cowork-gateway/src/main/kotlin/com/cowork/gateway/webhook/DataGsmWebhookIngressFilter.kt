package com.cowork.gateway.webhook

import com.cowork.gateway.deployment.DeploymentStateCache
import com.cowork.gateway.response.model.CommonApiResponse
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.time.Duration

@Component
class DataGsmWebhookIngressFilter(
    private val verifier: DataGsmWebhookVerifier,
    private val queue: DataGsmWebhookQueue,
    private val states: DeploymentStateCache,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : GlobalFilter,
    Ordered {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getOrder(): Int = -1100

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        if (exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)?.id != "authorization-webhook") {
            return chain.filter(exchange)
        }
        if (!verifier.configured()) return respond(exchange, HttpStatus.SERVICE_UNAVAILABLE, "webhook_not_configured")
        if (exchange.request.headers.contentLength > MAX_BODY_BYTES) {
            return respond(exchange, HttpStatus.CONTENT_TOO_LARGE, "payload_too_large")
        }
        return DataBufferUtils.join(exchange.request.body, MAX_BODY_BYTES).map { buffer ->
            try {
                ByteArray(buffer.readableByteCount()).also(buffer::read)
            } finally {
                DataBufferUtils.release(buffer)
            }
        }.defaultIfEmpty(ByteArray(0)).flatMap { body ->
            val signature = exchange.request.headers.getFirst("X-DataGSM-Signature")
            if (!verifier.validSignature(body, signature)) {
                return@flatMap respond(exchange, HttpStatus.UNAUTHORIZED, "invalid_signature")
            }
            if (!verifier.validEnvelope(body)) {
                return@flatMap respond(exchange, HttpStatus.BAD_REQUEST, "invalid_payload")
            }
            queue.accept(body, signature!!)
                .onErrorResume { error ->
                    logger.warn("Failed to reach DataGSM durable queue: {}", error.javaClass.simpleName)
                    Mono.just(false)
                }
                .flatMap { accepted ->
                    if (accepted) {
                        respond(exchange, HttpStatus.OK, "accepted", error = false)
                    } else {
                        fallback(exchange, chain, body)
                    }
                }
        }.onErrorResume(DataBufferLimitException::class.java) {
            respond(exchange, HttpStatus.CONTENT_TOO_LARGE, "payload_too_large")
        }
    }

    private fun fallback(exchange: ServerWebExchange, chain: GatewayFilterChain, body: ByteArray): Mono<Void> {
        if (states.snapshot()?.containsKey("cowork-authorization") != false) {
            return respond(exchange, HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable")
        }
        val replay = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getBody() = Flux.defer {
                Flux.just(exchange.response.bufferFactory().wrap(body))
            }
        }
        val response = object : ServerHttpResponseDecorator(exchange.response) {
            override fun setStatusCode(status: HttpStatusCode?): Boolean =
                super.setStatusCode(if (status?.is5xxServerError == true) HttpStatus.SERVICE_UNAVAILABLE else status)
        }
        return chain.filter(exchange.mutate().request(replay).response(response).build())
            .timeout(Duration.ofSeconds(8))
            .doOnSuccess {
                val accepted = exchange.response.statusCode?.is2xxSuccessful ?: true
                val outcome = if (accepted) "direct_fallback" else "temporarily_unavailable"
                meterRegistry.counter("gateway.datagsm.ingress", "outcome", outcome).increment()
            }
            .onErrorResume { error ->
                logger.warn("Failed to reach DataGSM direct inbox fallback: {}", error.javaClass.simpleName)
                if (exchange.response.isCommitted) {
                    Mono.empty()
                } else {
                    respond(exchange, HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable")
                }
            }
    }

    private fun respond(
        exchange: ServerWebExchange,
        status: HttpStatus,
        value: String,
        error: Boolean = true,
    ): Mono<Void> {
        meterRegistry.counter("gateway.datagsm.ingress", "outcome", value).increment()
        val result = if (error) {
            val message = when (value) {
                "webhook_not_configured" -> "웹훅을 처리할 수 없습니다."
                "payload_too_large" -> "요청 본문이 너무 큽니다."
                "invalid_signature" -> "유효하지 않은 서명입니다."
                "invalid_payload" -> "유효하지 않은 요청 본문입니다."
                else -> "웹훅을 일시적으로 접수할 수 없습니다."
            }
            CommonApiResponse.error(status, message)
        } else {
            CommonApiResponse.success(mapOf("status" to value))
        }
        val bytes = objectMapper.writeValueAsBytes(result)
        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        exchange.response.headers.cacheControl = "no-store"
        exchange.response.headers.contentLength = bytes.size.toLong()
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
    }

    private companion object {
        const val MAX_BODY_BYTES = 1 shl 20
    }
}
