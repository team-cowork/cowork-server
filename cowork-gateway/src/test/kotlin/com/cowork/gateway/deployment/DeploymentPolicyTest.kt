package com.cowork.gateway.deployment

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.springframework.data.redis.core.ReactiveHashOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.publisher.Flux
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.AbstractMap

class DeploymentPolicyTest :
    DescribeSpec({
        describe("배포 안내 대상 판정") {
            val matcher = DeploymentRouteMatcher(
                mapOf("cowork-user" to "http://127.0.0.1:18082", "cowork-chat" to "http://127.0.0.1:18087"),
            )
            it("URL 경로가 아니라 실제 라우트 목적지로 서비스를 정한다") {
                matcher.serviceFor(URI.create("http://127.0.0.1:18082/api/channel/teams")) shouldBe "cowork-user"
                matcher.serviceFor(URI.create("ws://127.0.0.1:18087/ws/chat")) shouldBe "cowork-chat"
                matcher.serviceFor(URI.create("http://127.0.0.1:19101/api/user/avatars")) shouldBe null
            }
            it("서로 다른 서비스가 같은 목적지를 공유하면 시작하지 않는다") {
                shouldThrow<IllegalArgumentException> {
                    DeploymentRouteMatcher(
                        mapOf("cowork-user" to "http://localhost:8080", "cowork-team" to "http://localhost:8080"),
                    )
                }
            }
        }

        describe("마지막 확인된 배포 상태") {
            val redis = mockk<ReactiveStringRedisTemplate>()
            val hash = mockk<ReactiveHashOperations<String, String, String>>()
            every { redis.opsForHash<String, String>() } returns hash
            val cache = DeploymentStateCache(
                redis,
                JsonMapper.builder().build(),
                SimpleMeterRegistry(),
                DeploymentProperties(),
            )

            it("Redis 조회 실패 시 기존 확인 상태를 유지한다") {
                every { hash.entries(DeploymentStateCache.REDIS_KEY) } returns Flux.just(
                    AbstractMap.SimpleEntry("cowork-team", "{\"runId\":\"42\",\"phase\":\"deploying\"}"),
                )
                cache.refresh()
                cache.snapshot()?.get("cowork-team") shouldBe DeploymentState("42", "deploying")

                every { hash.entries(DeploymentStateCache.REDIS_KEY) } returns Flux.error(IllegalStateException("down"))
                cache.refresh()
                cache.snapshot()?.get("cowork-team") shouldBe DeploymentState("42", "deploying")
            }
        }
    })
