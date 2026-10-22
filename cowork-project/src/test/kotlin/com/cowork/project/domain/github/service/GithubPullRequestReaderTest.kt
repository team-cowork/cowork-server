package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import com.cowork.project.domain.github.entity.GithubPullRequestProjectionFile
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.repository.GithubPullRequestProjectionRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Instant

class GithubPullRequestReaderTest :
    DescribeSpec({

        lateinit var repository: GithubPullRequestProjectionRepository
        lateinit var githubAppClient: GithubAppClient
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var reader: GithubPullRequestReader

        beforeEach {
            repository = mockk()
            githubAppClient = mockk()
            callExecutor = mockk()
            reader = GithubPullRequestReader(repository, githubAppClient, callExecutor)

            every { callExecutor.execute(any<() -> List<GithubPullRequestFileResDto>>()) } answers {
                firstArg<() -> List<GithubPullRequestFileResDto>>().invoke()
            }
        }

        fun projection(files: MutableList<GithubPullRequestProjectionFile> = mutableListOf()) = GithubPullRequestProjection(
            id = "my-org/my-repo#7",
            owner = "my-org",
            repo = "my-repo",
            number = 7,
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
            describe("listFiles 메서드는") {
                context("projection이 없는 경우") {
                    it("GithubAppClient로 직접 조회한다") {
                        every { repository.findByOwnerAndRepoAndNumber("my-org", "my-repo", 7) } returns null
                        every { githubAppClient.listPullRequestFiles("my-org", "my-repo", 7) } returns listOf(
                            GithubPullRequestFileResDto("a.kt", "modified", 1, 0, null),
                        )

                        val result = reader.listFiles("my-org", "my-repo", 7)

                        result.map { it.filename } shouldBe listOf("a.kt")
                    }
                }

                context("projection에 파일 목록이 있는 경우") {
                    it("로컬 projection에서 반환한다") {
                        every { repository.findByOwnerAndRepoAndNumber("my-org", "my-repo", 7) } returns
                            projection(mutableListOf(GithubPullRequestProjectionFile("b.kt", "added", 5, 0, "patch")))

                        val result = reader.listFiles("my-org", "my-repo", 7)

                        result.map { it.filename } shouldBe listOf("b.kt")
                    }
                }
            }

            describe("findAuthor 메서드는") {
                context("projection이 있는 경우") {
                    it("로컬 projection의 작성자를 반환한다") {
                        every { repository.findByOwnerAndRepoAndNumber("my-org", "my-repo", 7) } returns projection()

                        reader.findAuthor("my-org", "my-repo", 7) shouldBe "octocat"
                    }
                }
            }
        }
    })
