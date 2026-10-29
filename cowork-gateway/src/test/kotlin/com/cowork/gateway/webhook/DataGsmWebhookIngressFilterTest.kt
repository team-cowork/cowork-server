package com.cowork.gateway.webhook

import com.cowork.gateway.deployment.DeploymentState
import com.cowork.gateway.deployment.DeploymentStateCache
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.net.URI

class DataGsmWebhookIngressFilterTest :
    DescribeSpec({
        val verifier = mockk<DataGsmWebhookVerifier>()
        val queue = mockk<DataGsmWebhookQueue>()
        val states = mockk<DeploymentStateCache>()
        val registry = SimpleMeterRegistry()
        val filter =
            DataGsmWebhookIngressFilter(verifier, queue, states, JsonMapper.builder().build(), registry)
        val body = "{\"id\":\"test\"}"
        val signature = "sha256=signature"

        fun exchange(): MockServerWebExchange {
            val result = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/authorization/events/datagsm")
                    .header("X-DataGSM-Signature", signature).body(body),
            )
            val route = mockk<Route>()
            every { route.id } returns "authorization-webhook"
            every { route.uri } returns URI.create("http://127.0.0.1:18081")
            result.attributes[GATEWAY_ROUTE_ATTR] = route
            return result
        }

        beforeTest {
            every { verifier.configured() } returns true
            every { verifier.validSignature(any(), signature) } returns true
            every { verifier.validEnvelope(any()) } returns true
            every { queue.accept(any(), signature) } returns Mono.error(IllegalStateException("redis unavailable"))
        }

        describe("웹훅 접수 저장소 장애") {
            it("영속 기록 확인 후 기존 공통 응답 구조로 200을 보낸다") {
                every { queue.accept(any(), signature) } returns Mono.just(true)
                val chain = mockk<GatewayFilterChain>(relaxed = true)
                val exchange = exchange()
                filter.filter(exchange, chain).block()
                val result = JsonMapper.builder().build().readTree(exchange.response.bodyAsString.block())
                exchange.response.statusCode shouldBe HttpStatus.OK
                result.get("code").asInt() shouldBe 200
                result.get("data").get("status").asString() shouldBe "accepted"
                verify(exactly = 0) { chain.filter(any()) }
            }

            it("Authorization이 정상인 경우 원본 본문을 기존 MySQL 접수 경로에 전달한다") {
                every { states.snapshot() } returns emptyMap()
                var forwarded = ""
                val chain = mockk<GatewayFilterChain>()
                every { chain.filter(any()) } answers {
                    val request = firstArg<ServerWebExchange>().request
                    DataBufferUtils.join(request.body).map { buffer ->
                        try {
                            ByteArray(buffer.readableByteCount()).also(buffer::read).toString(Charsets.UTF_8)
                        } finally {
                            DataBufferUtils.release(buffer)
                        }
                    }.doOnNext { forwarded = it }.then()
                }
                val exchange = exchange()
                filter.filter(exchange, chain).block()
                forwarded shouldBe body
            }

            it("Authorization이 배포 중이면 직접 전달하지 않고 503으로 실패한다") {
                every { states.snapshot() } returns mapOf("cowork-authorization" to DeploymentState("42", "deploying"))
                val chain = mockk<GatewayFilterChain>(relaxed = true)
                val exchange = exchange()
                filter.filter(exchange, chain).block()
                exchange.response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                verify(exactly = 0) { chain.filter(any()) }
            }

            it("Authorization의 실패 응답은 접수 성공으로 집계하지 않는다") {
                every { states.snapshot() } returns emptyMap()
                val chain = mockk<GatewayFilterChain>()
                every { chain.filter(any()) } answers {
                    firstArg<ServerWebExchange>().response.statusCode = HttpStatus.BAD_GATEWAY
                    Mono.empty()
                }
                val counter = registry.counter("gateway.datagsm.ingress", "outcome", "temporarily_unavailable")
                val before = counter.count()
                val exchange = exchange()
                filter.filter(exchange, chain).block()
                exchange.response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                counter.count() shouldBe before + 1
            }
        }
    })
