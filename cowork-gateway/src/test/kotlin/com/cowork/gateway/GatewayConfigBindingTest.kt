package com.cowork.gateway

import com.cowork.gateway.filter.ApiResponseWrapperProperties
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
                val responseWrapperProperties = binder
                    .bind("gateway.response-wrapper", ApiResponseWrapperProperties::class.java)
                    .orElseGet(::ApiResponseWrapperProperties)

                it("현재 Spring Cloud Gateway 버전에 라우트를 바인딩한다") {
                    gatewayProperties.routes.map { it.id } shouldContainAll listOf(
                        "chat-service",
                        "dm-open",
                    )
                }

                it("모든 외부 HTTP API를 소유 모듈 namespace로 라우팅한다") {
                    val expectedPaths = mapOf(
                        "authorization-service" to setOf("/api/authorization/auth/**"),
                        "authorization-webhook" to setOf("/api/authorization/events/datagsm"),
                        "user-service" to setOf("/api/user/users/**"),
                        "team-service" to setOf("/api/team/teams/**"),
                        "project-team-service" to setOf("/api/project/teams/*/projects/**"),
                        "project-service" to setOf("/api/project/projects/**"),
                        "team-channel-service" to setOf(
                            "/api/channel/teams/*/channels",
                            "/api/channel/teams/*/channels/**",
                        ),
                        "project-channel-service" to setOf(
                            "/api/channel/projects/*/channels",
                            "/api/channel/projects/*/channels/**",
                        ),
                        "channel-service" to setOf("/api/channel/channels/**"),
                        "channel-service-search" to setOf("/api/channel/search/channels"),
                        "dm-open" to setOf("/api/channel/dms"),
                        "roadmap-service" to setOf("/api/roadmap/roadmaps/**"),
                        "preference-service" to setOf("/api/preference/preferences/**"),
                        "live-service" to setOf("/api/voice/live/**"),
                        "voice-service-webhook" to setOf("/api/voice/voice/webhook"),
                        "voice-service" to setOf("/api/voice/voice/**"),
                        "notification-service-sse" to setOf("/api/notification/notifications/stream"),
                        "notification-service" to setOf("/api/notification/notifications/**"),
                    )

                    expectedPaths.forEach { (routeId, paths) ->
                        val route = gatewayProperties.routes.first { it.id == routeId }
                        route.predicates
                            .filter { it.name == "Path" }
                            .flatMapTo(mutableSetOf()) { it.args.values } shouldBe paths
                        route.filters.flatMap { it.args.values } shouldContain "2"
                    }
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
                    dmOpenRoute.predicates.flatMap { it.args.values } shouldContain "/api/channel/dms"
                    dmOpenRoute.predicates.flatMap { it.args.values } shouldContain "POST"
                    dmOpenRoute.filters.flatMap { it.args.values } shouldContain "2"
                }

                it("더 구체적인 webhook과 SSE route를 일반 route보다 우선한다") {
                    val voiceWebhook = gatewayProperties.routes.first { it.id == "voice-service-webhook" }
                    val notificationSse = gatewayProperties.routes.first { it.id == "notification-service-sse" }

                    voiceWebhook.order shouldBe -1
                    notificationSse.order shouldBe -1
                    notificationSse.metadata["response-timeout"] shouldBe -1
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

                it("응답 wrapper의 최대 집계 크기를 1MB로 제한한다") {
                    responseWrapperProperties.maxWrappableBytes() shouldBe 1024 * 1024
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
