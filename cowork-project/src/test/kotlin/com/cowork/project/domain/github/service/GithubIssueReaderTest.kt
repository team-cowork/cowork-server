package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubIssueProjection
import com.cowork.project.domain.github.repository.GithubIssueProjectionRepository
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.Optional

class GithubIssueReaderTest :
    DescribeSpec({

        lateinit var repository: GithubIssueProjectionRepository
        lateinit var markerRepository: GithubSyncMarkerRepository
        lateinit var reader: GithubIssueReader

        beforeEach {
            repository = mockk()
            markerRepository = mockk()
            val repoProjectionRepository = mockk<GithubRepoProjectionRepository>()
            every { repoProjectionRepository.findById(any()) } returns Optional.empty()
            reader = GithubIssueReader(repository, GithubSyncMarkers(markerRepository, repoProjectionRepository))
        }

        fun projection(number: Int) = GithubIssueProjection(
            id = "my-org/my-repo#$number",
            owner = "my-org",
            repo = "my-repo",
            number = number,
            title = "issue-$number",
            author = "octocat",
            state = "open",
            htmlUrl = "https://github.com/my-org/my-repo/issues/$number",
            labels = mutableListOf("bug"),
            githubCreatedAt = "2026-01-01T00:00:00Z",
            githubUpdatedAt = "2026-01-02T00:00:00Z",
            sourceOccurredAt = Instant.EPOCH,
        )

        describe("GithubIssueReader 클래스의") {
            describe("listIssues 메서드는") {
                context("repo의 이슈 동기화 마커가 없는 경우") {
                    it("목록을 조회하지 않고 503을 던진다") {
                        every { markerRepository.existsById("ISSUE:my-org/my-repo") } returns false

                        val ex = shouldThrow<ExpectedException> { reader.listIssues("my-org", "my-repo", "open") }

                        ex.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                        verify(exactly = 0) {
                            repository.findTop100ByOwnerAndRepoAndStateAndDeletedFalseOrderByNumberDesc(
                                any(),
                                any(),
                                any(),
                            )
                        }
                    }
                }

                context("특정 상태를 요청한 경우") {
                    it("상태와 삭제 제외 조건으로 제한 조회한 결과를 그 순서대로 반환한다") {
                        every { markerRepository.existsById("ISSUE:my-org/my-repo") } returns true
                        every {
                            repository.findTop100ByOwnerAndRepoAndStateAndDeletedFalseOrderByNumberDesc(
                                "my-org",
                                "my-repo",
                                "open",
                            )
                        } returns listOf(projection(12), projection(3))

                        val result = reader.listIssues("my-org", "my-repo", "open")

                        result.map { it.number } shouldBe listOf(12, 3)
                        result.first().labels shouldBe listOf("bug")
                    }
                }

                context("all 상태를 요청한 경우") {
                    it("상태 조건 없이 삭제 제외 조건으로만 제한 조회한다") {
                        every { markerRepository.existsById("ISSUE:my-org/my-repo") } returns true
                        every {
                            repository.findTop100ByOwnerAndRepoAndDeletedFalseOrderByNumberDesc("my-org", "my-repo")
                        } returns listOf(projection(3))

                        val result = reader.listIssues("my-org", "my-repo", "all")

                        result.map { it.number } shouldBe listOf(3)
                    }
                }
            }
        }
    })
