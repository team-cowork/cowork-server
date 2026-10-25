package com.cowork.gateway.webhook

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class WebhookSignatureGatewayFilterFactory(
    @param:Value("\${WEBHOOK_URL_SIGNING_KEY:\${webhook.url-signing-key:}}") encodedKey: String,
) : AbstractGatewayFilterFactory<WebhookSignatureGatewayFilterFactory.Config>(Config::class.java) {
    class Config

    private val verifier = WebhookUrlSignatureVerifier(encodedKey)

    override fun apply(config: Config): GatewayFilter = GatewayFilter { exchange, chain ->
        val variables = ServerWebExchangeUtils.getUriTemplateVariables(exchange)
        val result = verifier.verify(variables["executionId"], variables["signature"])
        if (result == WebhookUrlSignatureVerifier.Verification.VALID) {
            chain.filter(exchange)
        } else {
            exchange.response.statusCode = if (result == WebhookUrlSignatureVerifier.Verification.UNAVAILABLE) {
                HttpStatus.SERVICE_UNAVAILABLE
            } else {
                HttpStatus.FORBIDDEN
            }
            exchange.response.headers.cacheControl = "no-store"
            exchange.response.setComplete()
        }
    }
}
