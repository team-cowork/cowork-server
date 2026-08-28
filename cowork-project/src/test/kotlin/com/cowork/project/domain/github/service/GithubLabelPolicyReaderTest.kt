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
import io.mockk.verify
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

        describe("GithubLabelPolicyReader") {
            it("preference projection에 있는 정책을 단건과 bulk로 읽는다") {
                val first = preference(5L, autoApply = false)
                val second = preference(6L, autoApply = true)
                every { repository.findById(5L) } returns Optional.of(first)
                every { repository.findAllById(listOf(5L, 6L)) } returns listOf(first, second)

                reader.readAutoApply(5L) shouldBe false
                reader.readAutoApplyBulk(listOf(5L, 6L)) shouldBe mapOf(5L to false, 6L to true)
                verify(atLeast = 2) { readinessGate.requireReady() }
            }

            it("상태 row가 없거나 DELETE면 preference 기본값 true를 사용한다") {
                every { repository.findById(5L) } returns Optional.empty()
                every { repository.findAllById(listOf(5L, 6L)) } returns listOf(preference(6L, false, deleted = true))

                reader.readAutoApply(5L) shouldBe true
                reader.readAutoApplyBulk(listOf(5L, 6L)) shouldBe mapOf(5L to true, 6L to true)
            }
        }
    })
