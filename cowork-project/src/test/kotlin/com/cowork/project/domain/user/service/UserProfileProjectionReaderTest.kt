package com.cowork.project.domain.user.service

import com.cowork.project.domain.user.entity.UserProfileProjection
import com.cowork.project.domain.user.repository.UserProfileProjectionRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.Optional

class UserProfileProjectionReaderTest :
    DescribeSpec({
        val repository = mockk<UserProfileProjectionRepository>()
        val readinessGate = mockk<ProjectionReadinessGate>()
        val reader = UserProfileProjectionReader(repository, readinessGate)

        fun profile(userId: Long, githubId: String) =
            UserProfileProjection(userId, githubId, deleted = false, sourceOccurredAt = Instant.EPOCH)

        beforeEach {
            every { readinessGate.requireReady() } just runs
            every { readinessGate.requireCurrent() } just runs
        }

        describe("resolveGithubId") {
            it("동기화된 profile의 githubId를 반환한다") {
                every { repository.findById(7L) } returns Optional.of(profile(7L, "octocat"))

                reader.resolveGithubId(7L) shouldBe "octocat"
            }

            it("현재 high-watermark까지 없는 profile은 NOT_FOUND를 반환한다") {
                every { repository.findById(7L) } returns Optional.empty() andThen Optional.empty()

                shouldThrow<ExpectedException> { reader.resolveGithubId(7L) }.statusCode shouldBe
                    HttpStatus.NOT_FOUND
            }
        }

        describe("resolveUniqueUserId") {
            it("githubId가 정확히 한 active user에 매핑될 때만 반환한다") {
                every { repository.findAllByGithubIdAndDeletedFalse("octocat") } returns listOf(profile(7L, "octocat"))

                reader.resolveUniqueUserId("octocat") shouldBe 7L
            }

            it("0건은 NOT_FOUND, transient duplicate는 SERVICE_UNAVAILABLE로 닫는다") {
                every { repository.findAllByGithubIdAndDeletedFalse("missing") } returns emptyList() andThen emptyList()
                every { repository.findAllByGithubIdAndDeletedFalse("duplicate") } returns
                    listOf(profile(7L, "duplicate"), profile(8L, "duplicate"))

                shouldThrow<ExpectedException> { reader.resolveUniqueUserId("missing") }.statusCode shouldBe
                    HttpStatus.NOT_FOUND
                shouldThrow<ExpectedException> { reader.resolveUniqueUserId("duplicate") }.statusCode shouldBe
                    HttpStatus.SERVICE_UNAVAILABLE
            }
        }
    })
