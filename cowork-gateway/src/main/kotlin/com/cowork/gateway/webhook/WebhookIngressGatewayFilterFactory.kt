package com.cowork.gateway.webhook

import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class WebhookIngressGatewayFilterFactory :
    AbstractGatewayFilterFactory<WebhookIngressGatewayFilterFactory.Config>(Config::class.java) {
    class Config

    override fun apply(config: Config): GatewayFilter = GatewayFilter { exchange, chain ->
        val clientIp = exchange.request.remoteAddress?.address?.hostAddress
        if (clientIp == null) {
            exchange.response.statusCode = HttpStatus.SERVICE_UNAVAILABLE
            return@GatewayFilter exchange.response.setComplete()
        }
        // 외부가 보낸 전달 헤더를 신뢰하지 않고 Gateway 연결에서 확인한 주소로 덮어쓴다.
        val request = exchange.request.mutate().headers { headers ->
            headers.set("X-Webhook-Client-IP", clientIp)
            headers.remove("X-User-Id")
            headers.remove("X-User-Role")
        }.build()
        chain.filter(exchange.mutate().request(request).build())
    }
}
