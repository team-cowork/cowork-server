package com.cowork.project.domain.github.service

import com.cowork.project.domain.githubPreference.entity.GithubRepoPreferenceProjection
import com.cowork.project.domain.githubPreference.repository.GithubRepoPreferenceProjectionRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.time.Instant
import java.util.Optional

class GithubLabelPolicyReaderTest :
    DescribeSpec({
        lateinit var repository: GithubRepoPreferenceProjectionRepository
        lateinit var readinessGate: ProjectionReadinessGate
        lateinit var reader: GithubLabelPolicyReader

        fun preference(id: Long, autoApply: Boolean, deleted: Boolean = false) = GithubRepoPreferenceProjection(
            repoId = id,
            labelAutoApply = autoApply,
            deleted = deleted,
            sourceOccurredAt = Instant.parse("2026-08-27T00:00:00Z"),
        )

        beforeEach {
            repository = mockk()
            readinessGate = mockk()
            every { readinessGate.requireReady() } just runs
            reader = GithubLabelPolicyReader(repository, readinessGate)
        }

        describe("GithubLabelPolicyReader 클래스의 readAutoApply 메서드는") {
            context("저장된 라벨 자동 적용 정책이 있으면") {
                it("저장된 값을 반환한다") {
                    every { repository.findById(5L) } returns Optional.of(preference(5L, autoApply = false))

                    reader.readAutoApply(5L) shouldBe false
                }
            }

            context("정책이 없거나 삭제됐으면") {
                it("기본값 true를 반환한다") {
                    every { repository.findById(5L) } returns Optional.empty()
                    every { repository.findById(6L) } returns Optional.of(preference(6L, false, deleted = true))

                    reader.readAutoApply(5L) shouldBe true
                    reader.readAutoApply(6L) shouldBe true
                }
            }
        }
    })
