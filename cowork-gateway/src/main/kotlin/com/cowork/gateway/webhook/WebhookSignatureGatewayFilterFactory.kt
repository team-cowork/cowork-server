package com.cowork.gateway.webhook

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class WebhookSignatureGatewayFilterFactory(
    @param:Value("\${WEBHOOK_URL_SIGNING_KEY:\${webhook.url-signing-key:}}") encodedKey: String,
    private val meterRegistry: MeterRegistry,
) : AbstractGatewayFilterFactory<WebhookSignatureGatewayFilterFactory.Config>(Config::class.java) {
    class Config

    private val verifier = WebhookUrlSignatureVerifier(encodedKey)

    override fun apply(config: Config): GatewayFilter = GatewayFilter { exchange, chain ->
        val variables = ServerWebExchangeUtils.getUriTemplateVariables(exchange)
        val result = verifier.verify(variables["executionId"], variables["signature"])
        if (result == WebhookUrlSignatureVerifier.Verification.VALID) {
            chain.filter(exchange)
        } else {
            // 잘못된 서명(403)과 서명 키 설정 오류(503)는 다른 라우트의 같은 상태 코드와 섞이지 않게 따로 센다
            Counter.builder(REJECTIONS_METRIC)
                .tag("reason", result.name.lowercase())
                .register(meterRegistry)
                .increment()
            exchange.response.statusCode = if (result == WebhookUrlSignatureVerifier.Verification.UNAVAILABLE) {
                HttpStatus.SERVICE_UNAVAILABLE
            } else {
                HttpStatus.FORBIDDEN
            }
            exchange.response.headers.cacheControl = "no-store"
            exchange.response.setComplete()
        }
    }

    companion object {
        private const val REJECTIONS_METRIC = "gateway.webhook.signature.rejections"
    }
}
