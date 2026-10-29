package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import com.cowork.project.domain.github.entity.GithubPullRequestProjectionFile
import com.cowork.project.domain.github.repository.GithubPullRequestProjectionRepository
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

class GithubPullRequestReaderTest :
    DescribeSpec({

        lateinit var repository: GithubPullRequestProjectionRepository
        lateinit var markerRepository: GithubSyncMarkerRepository
        lateinit var reader: GithubPullRequestReader

        beforeEach {
            repository = mockk()
            markerRepository = mockk()
            val repoProjectionRepository = mockk<GithubRepoProjectionRepository>()
            every { repoProjectionRepository.findById(any()) } returns Optional.empty()
            reader = GithubPullRequestReader(repository, GithubSyncMarkers(markerRepository, repoProjectionRepository))
        }

        fun projection(
            files: MutableList<GithubPullRequestProjectionFile> = mutableListOf(),
            number: Int = 7,
        ) = GithubPullRequestProjection(
            id = "my-org/my-repo#$number",
            owner = "my-org",
            repo = "my-repo",
            number = number,
            title = "Add feature",
            author = "octocat",
            state = "open",
            draft = false,
            merged = false,
            htmlUrl = "https://github.com/my-org/my-repo/pull/7",
            headRef = "feature",
            baseRef = "main",
            githubCreatedAt = "2026-01-01T00:00:00Z",
            githubUpdatedAt = "2026-01-02T00:00:00Z",
            files = files,
            sourceOccurredAt = Instant.EPOCH,
        )

        describe("GithubPullRequestReader 클래스의") {
            describe("listPullRequests 메서드는") {
                context("repo의 PR 동기화 마커가 없는 경우") {
                    it("목록을 조회하지 않고 503을 던진다") {
                        every { markerRepository.existsById("PULL_REQUEST:my-org/my-repo") } returns false

                        val ex = shouldThrow<ExpectedException> { reader.listPullRequests("my-org", "my-repo", "open") }

                        ex.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                        verify(exactly = 0) {
                            repository.findTop100ByOwnerAndRepoAndStateOrderByNumberDesc(any(), any(), any())
                        }
                    }
                }

                context("특정 상태를 요청한 경우") {
                    it("상태 조건으로 제한 조회한 결과를 그 순서대로 반환한다") {
                        every { markerRepository.existsById("PULL_REQUEST:my-org/my-repo") } returns true
                        every {
                            repository.findTop100ByOwnerAndRepoAndStateOrderByNumberDesc("my-org", "my-repo", "open")
                        } returns listOf(projection(number = 9), projection(number = 7))

                        val result = reader.listPullRequests("my-org", "my-repo", "open")

                        result.map { it.number } shouldBe listOf(9, 7)
                    }
                }

                context("all 상태를 요청한 경우") {
                    it("상태 조건 없이 제한 조회한다") {
                        every { markerRepository.existsById("PULL_REQUEST:my-org/my-repo") } returns true
                        every { repository.findTop100ByOwnerAndRepoOrderByNumberDesc("my-org", "my-repo") } returns
                            listOf(projection(number = 7))

                        val result = reader.listPullRequests("my-org", "my-repo", "all")

                        result.map { it.number } shouldBe listOf(7)
                    }
                }
            }

            describe("listFiles 메서드는") {
                context("repo의 PR 동기화 마커가 없는 경우") {
                    it("503을 던진다") {
                        every { markerRepository.existsById("PULL_REQUEST:my-org/my-repo") } returns false

                        val ex = shouldThrow<ExpectedException> { reader.listFiles("my-org", "my-repo", 7) }

                        ex.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                    }
                }

                context("동기화는 끝났지만 PR이 projection에 없는 경우") {
                    it("404를 던진다") {
                        every { markerRepository.existsById("PULL_REQUEST:my-org/my-repo") } returns true
                        every { repository.findByOwnerAndRepoAndNumber("my-org", "my-repo", 7) } returns null

                        val ex = shouldThrow<ExpectedException> { reader.listFiles("my-org", "my-repo", 7) }

                        ex.statusCode shouldBe HttpStatus.NOT_FOUND
                    }
                }

                context("동기화가 끝났고 PR이 projection에 있는 경우") {
                    it("projection의 파일 목록을 반환한다") {
                        every { markerRepository.existsById("PULL_REQUEST:my-org/my-repo") } returns true
                        every { repository.findByOwnerAndRepoAndNumber("my-org", "my-repo", 7) } returns projection(
                            mutableListOf(GithubPullRequestProjectionFile("b.kt", "added", 3, 0, "@@ patch")),
                        )

                        val result = reader.listFiles("my-org", "my-repo", 7)

                        result.map { it.filename } shouldBe listOf("b.kt")
                    }
                }
            }
        }
    })
