package com.cowork.project.domain.github.service

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.time.Duration
import java.time.Instant

class GithubSyncRequestSchedulerTest :
    DescribeSpec({

        val now = Instant.parse("2026-10-22T12:00:00Z")
        val scheduler = GithubSyncRequestScheduler(
            projectGithubRepoRepository = mockk(),
            installationRepository = mockk(),
            markerRepository = mockk(),
            requestRepository = mockk(),
            publisher = mockk(),
            snapshotLock = mockk(),
            requestCooldownMs = Duration.ofMinutes(30).toMillis(),
            staleAfterMs = Duration.ofDays(7).toMillis(),
        )

        describe("GithubSyncRequestScheduler 클래스의 needsRequest 메서드는") {
            context("마커가 하나라도 없고 요청한 적도 없는 경우") {
                it("요청한다") {
                    scheduler.needsRequest(listOf(now, null), null, now) shouldBe true
                }
            }

            context("쿨다운 안에 이미 요청한 경우") {
                it("마커가 없어도 다시 요청하지 않는다") {
                    scheduler.needsRequest(listOf(null), now.minus(Duration.ofMinutes(10)), now) shouldBe false
                }
            }

            context("쿨다운이 지났는데도 마커가 없는 경우") {
                it("다시 요청한다") {
                    scheduler.needsRequest(listOf(null), now.minus(Duration.ofMinutes(31)), now) shouldBe true
                }
            }

            context("모든 마커가 오래되지 않은 경우") {
                it("요청하지 않는다") {
                    scheduler.needsRequest(listOf(now.minus(Duration.ofDays(1))), null, now) shouldBe false
                }
            }

            context("마커 하나가 오래된 경우") {
                it("다시 동기화를 요청한다") {
                    val markers = listOf(now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(8)))

                    scheduler.needsRequest(markers, null, now) shouldBe true
                }
            }
        }
    })
