package com.cowork.gateway

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.config.GlobalCorsProperties
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import java.nio.file.Path

class GatewayConfigBindingTest :
    DescribeSpec({
        listOf("local", "prod").forEach { profile ->
            describe("cowork-gateway-$profile 설정은") {
                val environment = loadGatewayEnvironment(profile)
                val binder = Binder.get(environment)
                val gatewayProperties = binder
                    .bind(GatewayProperties.PREFIX, GatewayProperties::class.java)
                    .orElseGet(::GatewayProperties)
                val globalCorsProperties = binder
                    .bind("${GatewayProperties.PREFIX}.globalcors", GlobalCorsProperties::class.java)
                    .orElseGet(::GlobalCorsProperties)

                it("현재 Spring Cloud Gateway 버전에 라우트를 바인딩한다") {
                    gatewayProperties.routes.map { it.id } shouldContainAll listOf(
                        "chat-service",
                        "dm-open",
                    )
                }

                it("chat HTTP API는 모듈 prefix만 제거하고 하위 서비스 path를 보존한다") {
                    val routes = gatewayProperties.routes
                    val routeIds = routes.map { it.id }
                    val chatRoute = routes.first { it.id == "chat-service" }
                    val removedRouteIds = setOf(
                        "chat-service-legacy",
                        "chat-service-team-unread",
                        "chat-service-search",
                        "chat-service-team-search",
                        "dm-service",
                        "block-service",
                        "chat-dm-open",
                        "chat-health",
                        "chat-asyncapi",
                    )

                    routeIds.intersect(removedRouteIds) shouldBe emptySet()
                    chatRoute.uri.toString() shouldBe "lb://cowork-chat"
                    chatRoute.predicates.flatMapTo(mutableSetOf()) { it.args.values } shouldBe setOf(
                        "/api/chat/chat/**",
                        "/api/chat/health",
                        "/api/chat/graphql",
                        "/api/chat/asyncapi.json",
                    )
                    chatRoute.filters.flatMap { it.args.values } shouldContain "2"
                }

                it("channel 소유 POST /dms는 Chat 네임스페이스에 섞지 않는다") {
                    val dmOpenRoute = gatewayProperties.routes.first { it.id == "dm-open" }

                    dmOpenRoute.uri.toString() shouldBe "lb://cowork-channel"
                    dmOpenRoute.predicates.flatMap { it.args.values } shouldContain "/api/dms"
                    dmOpenRoute.predicates.flatMap { it.args.values } shouldContain "POST"
                    dmOpenRoute.filters.flatMap { it.args.values } shouldContain "1"
                }

                it("라우트 predicate와 무관하게 전역 CORS를 preflight에 적용한다") {
                    globalCorsProperties.corsConfigurations shouldContainKey "/**"
                    val corsConfiguration = globalCorsProperties.corsConfigurations.getValue("/**")

                    corsConfiguration.allowedOrigins.orEmpty() shouldContain "http://localhost:3000"
                    corsConfiguration.allowedMethods.orEmpty() shouldContainAll listOf("GET", "POST", "OPTIONS")
                    corsConfiguration.allowCredentials shouldBe true
                    environment.getProperty(
                        "${GatewayProperties.PREFIX}.globalcors.add-to-simple-url-handler-mapping",
                        Boolean::class.java,
                        false,
                    ) shouldBe true
                }
            }
        }

        it("local과 prod의 route id 집합이 같다") {
            gatewayRouteIds("local") shouldBe gatewayRouteIds("prod")
        }

        it("보호 대상 라우트의 local과 prod rate limit이 같다") {
            listOf(
                "authorization-service",
                "user-service",
                "team-service",
                "channel-service",
                "voice-service",
            ).forEach { routeId ->
                gatewayRateLimiterArgs("local", routeId) shouldBe
                    gatewayRateLimiterArgs("prod", routeId)
            }
        }
    })

private fun gatewayRouteIds(profile: String): Set<String> {
    val gatewayProperties = Binder.get(loadGatewayEnvironment(profile))
        .bind(GatewayProperties.PREFIX, GatewayProperties::class.java)
        .orElseGet(::GatewayProperties)

    return gatewayProperties.routes.mapNotNullTo(mutableSetOf()) { it.id }
}

private fun gatewayRateLimiterArgs(profile: String, routeId: String): Map<String, String> {
    val gatewayProperties = Binder.get(loadGatewayEnvironment(profile))
        .bind(GatewayProperties.PREFIX, GatewayProperties::class.java)
        .orElseGet(::GatewayProperties)
    val route = gatewayProperties.routes.first { it.id == routeId }

    return route.filters.first { it.name == "RequestRateLimiter" }.args
}

private fun loadGatewayEnvironment(profile: String): StandardEnvironment {
    val configDirectory = requireNotNull(System.getProperty("gateway.config.dir"))
    val resource = FileSystemResource(Path.of(configDirectory, "cowork-gateway-$profile.yml"))
    val propertySources = YamlPropertySourceLoader().load("gateway-$profile", resource)

    return StandardEnvironment().apply {
        propertySources.reversed().forEach { propertySource ->
            this.propertySources.addFirst(propertySource)
        }
        this.propertySources.addFirst(
            MapPropertySource(
                "test-overrides",
                mapOf("PUBLIC_WEB_ORIGIN" to "http://localhost:3000"),
            ),
        )
    }
}
