package com.cowork.gateway.metrics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ATTR
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.URI

class GatewayRequestObservationConventionTest :
    DescribeSpec({
        val properties = GatewayProperties().apply {
            routes = listOf(
                RouteDefinition().apply {
                    setId("users")
                    setUri(URI.create("http://localhost:8081"))
                    predicates = listOf(PredicateDefinition("Path=/api/user/users/**"))
                },
                RouteDefinition().apply {
                    setId("user-health")
                    setUri(URI.create("http://localhost:8081"))
                    predicates = listOf(PredicateDefinition("Path=/api/user/health, /api/user/health/ready"))
                },
                RouteDefinition().apply {
                    setId("user-profile")
                    setUri(URI.create("http://localhost:8081"))
                    predicates = listOf(PredicateDefinition("Path=/api/user/users/{id}/profile"))
                },
            )
        }
        val convention = GatewayRequestObservationConvention(properties)

        fun context(
            path: String,
            status: HttpStatus = HttpStatus.OK,
            method: HttpMethod = HttpMethod.GET,
            routePattern: String? = null,
            handlerPattern: String? = null,
            matchedRouteId: String = "selected-route",
        ): ServerRequestObservationContext {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create(path)))
            exchange.response.statusCode = status
            if (routePattern != null) {
                exchange.attributes[GATEWAY_ROUTE_ATTR] = Route.async()
                    .id("selected-route")
                    .uri("http://localhost:8081")
                    .predicate { true }
                    .build()
                exchange.attributes[GATEWAY_PREDICATE_MATCHED_PATH_ATTR] = routePattern
                exchange.attributes[GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR] = matchedRouteId
            }
            return ServerRequestObservationContext(exchange.request, exchange.response, exchange.attributes).apply {
                pathPattern = handlerPattern
            }
        }

        fun uri(context: ServerRequestObservationContext): String =
            convention.getLowCardinalityKeyValues(context).first { it.key == "uri" }.value

        describe("Gateway HTTP 요청 경로 분류") {
            it("프록시 요청은 실제 선택된 라우트의 경로 패턴을 사용한다") {
                uri(context("/api/user/users/42", routePattern = "/api/user/users/**")) shouldBe "/api/user/users/**"
            }

            it("다른 라우트 후보의 경로보다 실제 선택된 라우트 패턴을 우선한다") {
                uri(context("/api/user/users/42/profile", routePattern = "/api/user/users/**")) shouldBe
                    "/api/user/users/**"
            }

            it("downstream의 404와 회로 차단기 응답도 원래 라우트로 분류한다") {
                uri(context("/api/user/users/42", HttpStatus.NOT_FOUND, routePattern = "/api/user/users/**")) shouldBe
                    "/api/user/users/**"
                uri(
                    context(
                        "/api/user/users/42",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        routePattern = "/api/user/users/**",
                        handlerPattern = "/fallback",
                    ),
                ) shouldBe "/api/user/users/**"
            }

            it("인증 거부와 CORS 응답은 설정에 있는 경로 패턴으로 분류한다") {
                uri(context("/api/user/users/42", HttpStatus.UNAUTHORIZED)) shouldBe "/api/user/users/**"
                uri(context("/api/user/users/me", method = HttpMethod.OPTIONS)) shouldBe "/api/user/users/**"
            }

            it("라우팅 전에는 가장 구체적인 패턴으로 분류하고 ID와 query를 라벨에 넣지 않는다") {
                listOf("42", "81", "private-name").forEach { id ->
                    uri(context("/api/user/users/$id/profile?token=secret", HttpStatus.FORBIDDEN)) shouldBe
                        "/api/user/users/{id}/profile"
                }
            }

            it("하나의 Path 조건에 있는 각 헬스체크 경로를 구분한다") {
                uri(context("/api/user/health")) shouldBe "/api/user/health"
                uri(context("/api/user/health/ready")) shouldBe "/api/user/health/ready"
            }

            it("Gateway 컨트롤러와 정적 리소스의 기존 경로를 유지한다") {
                uri(context("/api/legal/license", handlerPattern = "/api/legal/license")) shouldBe "/api/legal/license"
                uri(context("/webjars/swagger.js", handlerPattern = "/webjars/**")) shouldBe "/webjars/**"
            }

            it("선택되지 않은 라우트 후보가 남긴 패턴을 사용하지 않는다") {
                uri(
                    context(
                        "/api/user/users/42",
                        routePattern = "/wrong/**",
                        matchedRouteId = "rejected-route",
                    ),
                ) shouldBe "/api/user/users/**"
            }

            it("등록되지 않은 경로는 원문 대신 Spring의 제한된 기본 분류를 유지한다") {
                uri(context("/random/private-value", HttpStatus.NOT_FOUND)) shouldBe "NOT_FOUND"
                uri(context("/random/private-value", HttpStatus.FOUND)) shouldBe "REDIRECTION"
                uri(context("/random/private-value", HttpStatus.UNAUTHORIZED)) shouldBe "UNKNOWN"
            }

            it("HTTP 메서드와 응답 코드 및 예외 라벨을 유지한다") {
                val request = context("/api/user/users/42", HttpStatus.UNAUTHORIZED, HttpMethod.POST)
                val tags = convention.getLowCardinalityKeyValues(request).associate { it.key to it.value }
                tags["method"] shouldBe "POST"
                tags["status"] shouldBe "401"
                tags["outcome"] shouldBe "CLIENT_ERROR"
                tags["exception"] shouldBe "none"
            }
        }
    })
