package com.cowork.gateway.deployment

import com.cowork.gateway.response.body.writeNoStoreJson
import com.cowork.gateway.response.model.CommonApiResponse
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.core.Ordered
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

@Component
class DeploymentGuidanceFilter(
    properties: DeploymentProperties,
    private val states: DeploymentStateCache,
    private val inflight: DeploymentInflight,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : GlobalFilter,
    Ordered {
    private val routes = DeploymentRouteMatcher(properties.destinations)

    override fun getOrder(): Int = -1000

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val route = exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)
        val service = route?.let { routes.serviceFor(it.uri) }
        val snapshot = states.snapshot()
        if (service != null && snapshot == null) {
            return respond(
                exchange,
                service,
                "SERVICE_STATE_UNAVAILABLE",
                "배포 상태를 확인하는 중입니다. 잠시 후 다시 시도해주세요.",
            )
        }
        if (service == null || exchange.request.method == HttpMethod.OPTIONS) {
            return chain.filter(exchange)
        }
        val state = snapshot?.get(service)
        if (state == null) {
            if (route.uri.scheme == "ws" || route.uri.scheme == "wss" || route.id == "notification-service-sse") {
                return chain.filter(exchange)
            }
            return Mono.defer {
                inflight.enter(service)
                Mono.defer { chain.filter(exchange) }.doFinally { inflight.leave(service) }
            }
        }

        val reason = if (state.phase == "deploying") "SERVICE_DEPLOYING" else "SERVICE_RECOVERING"
        val message = if (state.phase == "deploying") {
            "서비스를 배포 중입니다. 잠시 후 다시 시도해주세요."
        } else {
            "서비스를 복구 중입니다. 잠시 후 다시 시도해주세요."
        }
        return respond(exchange, service, reason, message)
    }

    private fun respond(exchange: ServerWebExchange, service: String?, reason: String, message: String): Mono<Void> {
        val status = HttpStatus.SERVICE_UNAVAILABLE
        meterRegistry.counter(
            "gateway.deployment.guidance.requests",
            "service",
            service ?: "unclassified",
            "reason",
            reason,
        ).increment()
        val data = mutableMapOf("reason" to reason)
        if (service != null) data["service"] = service.removePrefix("cowork-")
        val body = objectMapper.writeValueAsBytes(
            CommonApiResponse(
                status.name,
                status.value(),
                message,
                data,
            ),
        )
        return exchange.response.writeNoStoreJson(status, body)
    }
}
