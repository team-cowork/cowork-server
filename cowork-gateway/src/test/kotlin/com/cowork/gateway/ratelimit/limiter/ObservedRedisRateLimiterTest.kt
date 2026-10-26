package com.cowork.gateway.ratelimit.limiter

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux

class ObservedRedisRateLimiterTest :
    DescribeSpec({
        val route = "user-service"

        fun limiter(
            redisResult: Flux<List<Long>>,
            replenishRate: Int,
            requestedTokens: Int = 1,
        ): Pair<ObservedRedisRateLimiter, SimpleMeterRegistry> {
            val redisTemplate = mockk<ReactiveStringRedisTemplate>()
            every {
                redisTemplate.execute(any<RedisScript<List<Long>>>(), any<List<String>>(), any<List<*>>())
            } returns redisResult
            val registry = SimpleMeterRegistry()
            val limiter = ObservedRedisRateLimiter(redisTemplate, mockk(), mockk(relaxed = true), registry)
            limiter.config[route] = RedisRateLimiter.Config()
                .setReplenishRate(replenishRate)
                .setBurstCapacity(replenishRate * 2L)
                .setRequestedTokens(requestedTokens)
            return limiter to registry
        }

        fun SimpleMeterRegistry.count(outcome: String): Double =
            find("gateway.ratelimit.events").tags("route", route, "outcome", outcome).counter()?.count() ?: 0.0

        describe("ObservedRedisRateLimiter 클래스의 isAllowed 메서드는") {
            context("토큰이 부족해 요청을 차단하면") {
                it("Retry-After를 더하고 차단 횟수를 기록한다") {
                    val (limiter, registry) = limiter(Flux.just(listOf(0L, 0L)), replenishRate = 20)

                    val response = limiter.isAllowed(route, "42").block()!!

                    response.isAllowed shouldBe false
                    response.headers["Retry-After"] shouldBe "1"
                    response.headers[RedisRateLimiter.REMAINING_HEADER] shouldBe "0"
                    registry.count("rejected") shouldBe 1.0
                    registry.count("redis_error") shouldBe 0.0
                }

                it("요청 토큰이 다시 채워지는 시간을 올림한 초로 안내한다") {
                    val (limiter, _) = limiter(Flux.just(listOf(0L, 0L)), replenishRate = 2, requestedTokens = 3)

                    limiter.isAllowed(route, "42").block()!!.headers["Retry-After"] shouldBe "2"
                }
            }

            context("Redis 호출이 실패하면") {
                it("요청을 허용하고 차단과 구분해 제한기 오류를 기록한다") {
                    val (limiter, registry) = limiter(
                        Flux.error(IllegalStateException("redis down")),
                        replenishRate = 20,
                    )

                    val response = limiter.isAllowed(route, "42").block()!!

                    response.isAllowed shouldBe true
                    response.headers.containsKey("Retry-After") shouldBe false
                    registry.count("redis_error") shouldBe 1.0
                    registry.count("rejected") shouldBe 0.0
                }
            }

            context("토큰이 남아 있으면") {
                it("응답을 바꾸지 않고 기록하지 않는다") {
                    val (limiter, registry) = limiter(Flux.just(listOf(1L, 9L)), replenishRate = 20)

                    val response = limiter.isAllowed(route, "42").block()!!

                    response.isAllowed shouldBe true
                    response.headers.containsKey("Retry-After") shouldBe false
                    registry.meters.isEmpty() shouldBe true
                }
            }
        }
    })
