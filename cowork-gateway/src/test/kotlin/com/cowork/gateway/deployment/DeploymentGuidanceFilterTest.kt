package com.cowork.gateway.deployment

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.net.URI

class DeploymentGuidanceFilterTest :
    DescribeSpec({
        val mapper = JsonMapper.builder().build()
        val states = mockk<DeploymentStateCache>()
        val inflight = DeploymentInflight()
        val filter = DeploymentGuidanceFilter(
            DeploymentProperties(mapOf("cowork-team" to "http://127.0.0.1:18085")),
            states,
            inflight,
            mapper,
            SimpleMeterRegistry(),
        )
        val chain = mockk<GatewayFilterChain>()
        every { chain.filter(any()) } returns Mono.empty()

        fun exchange(uri: String): MockServerWebExchange {
            val result = MockServerWebExchange.from(MockServerHttpRequest.get("/api/anything"))
            val route = mockk<Route>()
            every { route.uri } returns URI.create(uri)
            every { route.id } returns "target-route"
            result.attributes[GATEWAY_ROUTE_ATTR] = route
            return result
        }

        describe("배포 안내 응답") {
            it("Redis 최초 동기화 전 배포 대상 요청만 503으로 차단한다") {
                every { states.snapshot() } returns null
                val target = exchange("http://127.0.0.1:18085")
                filter.filter(target, chain).block()
                val body = mapper.readTree(target.response.bodyAsString.block())
                target.response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                target.response.headers.cacheControl shouldBe "no-store"
                body.get("code").asInt() shouldBe 503
                body.get("data").get("reason").asString() shouldBe "SERVICE_STATE_UNAVAILABLE"
                body.get("data").get("service").asString() shouldBe "team"

                val unclassified = exchange("http://127.0.0.1:19999")
                filter.filter(unclassified, chain).block()
                unclassified.response.statusCode shouldBe null
                verify(exactly = 1) { chain.filter(unclassified) }

                val local = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"))
                filter.filter(local, chain).block()
                local.response.statusCode shouldBe null
                verify(exactly = 1) { chain.filter(local) }
            }

            it("대상 라우트만 503과 안정적인 사유 값을 받는다") {
                every { states.snapshot() } returns mapOf("cowork-team" to DeploymentState("run-1", "deploying"))
                val target = exchange("http://127.0.0.1:18085")
                filter.filter(target, chain).block()
                val body = mapper.readTree(target.response.bodyAsString.block())
                target.response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                target.response.headers.cacheControl shouldBe "no-store"
                body.get("code").asInt() shouldBe 503
                body.get("data").get("reason").asString() shouldBe "SERVICE_DEPLOYING"
                body.get("data").get("service").asString() shouldBe "team"

                val healthy = exchange("http://127.0.0.1:18082")
                filter.filter(healthy, chain).block()
                healthy.response.statusCode shouldBe null
            }
            it("복구 중 상태는 별도 사유 값을 보낸다") {
                every { states.snapshot() } returns mapOf("cowork-team" to DeploymentState("run-1", "recovering"))
                val target = exchange("http://127.0.0.1:18085")
                filter.filter(target, chain).block()
                mapper.readTree(target.response.bodyAsString.block()).get("data").get("reason").asString() shouldBe
                    "SERVICE_RECOVERING"
            }
            it("전달 중인 일반 HTTP 요청을 드레인 카운터에 반영한다") {
                every { states.snapshot() } returns emptyMap()
                every { chain.filter(any()) } returns Mono.never()
                val request = filter.filter(exchange("http://127.0.0.1:18085"), chain).subscribe()
                inflight.snapshot()["cowork-team"] shouldBe 1
                request.dispose()
                inflight.snapshot()["cowork-team"] shouldBe null
            }
        }
    })
