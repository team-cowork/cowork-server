package com.cowork.gateway.metrics

import io.micrometer.common.KeyValue
import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ATTR
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext
import org.springframework.stereotype.Component
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

@Component
class GatewayRequestObservationConvention(gatewayProperties: GatewayProperties) :
    DefaultServerRequestObservationConvention() {
    // Security and CORS can finish before routing; classify only against configured, bounded patterns.
    private val pathPatterns = gatewayProperties.routes
        .filter { it.isEnabled }
        .flatMap { it.predicates }
        .filter { it.name == "Path" }
        .flatMap { it.args.values }
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.startsWith("/") }
        .distinct()
        .map { PathPatternParser.defaultInstance.parse(it) }
        .sortedWith(PathPattern.SPECIFICITY_COMPARATOR)

    override fun uri(context: ServerRequestObservationContext): KeyValue {
        val route = context.attributes[GATEWAY_ROUTE_ATTR] as? Route
        val matchedRouteId = context.attributes[GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR]
        if (route != null && route.id == matchedRouteId) {
            val pattern = context.attributes[GATEWAY_PREDICATE_MATCHED_PATH_ATTR] as? String
            if (pattern != null) return KeyValue.of("uri", pattern)
        }

        if (context.pathPattern != null) return super.uri(context)

        val path = context.carrier?.path?.pathWithinApplication()
        val pattern = path?.let { requestPath -> pathPatterns.firstOrNull { it.matches(requestPath) } }
        return pattern?.let { KeyValue.of("uri", it.patternString) } ?: super.uri(context)
    }
}
