package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import com.cowork.project.domain.github.repository.GithubOrgBackfillStatusRepository
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Instant

class GithubRepoReaderTest :
    DescribeSpec({

        lateinit var backfillStatusRepository: GithubOrgBackfillStatusRepository
        lateinit var repoProjectionRepository: GithubRepoProjectionRepository
        lateinit var githubAppClient: GithubAppClient
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var reader: GithubRepoReader

        beforeEach {
            backfillStatusRepository = mockk()
            repoProjectionRepository = mockk()
            githubAppClient = mockk()
            callExecutor = mockk()
            reader = GithubRepoReader(backfillStatusRepository, repoProjectionRepository, githubAppClient, callExecutor)

            every { callExecutor.execute(any<() -> List<GithubRepoSummaryResDto>>()) } answers {
                firstArg<() -> List<GithubRepoSummaryResDto>>().invoke()
            }
        }

        describe("GithubRepoReader 클래스의") {
            describe("listOrgRepos 메서드는") {
                context("org의 백필이 아직 끝나지 않은 경우") {
                    it("GithubAppClient로 직접 조회한다") {
                        every { backfillStatusRepository.existsById("my-org") } returns false
                        every { githubAppClient.listOrgRepos("my-org") } returns listOf(
                            GithubRepoSummaryResDto("my-repo", "my-org/my-repo", false, "https://github.com/my-org/my-repo"),
                        )

                        val result = reader.listOrgRepos("my-org")

                        result.map { it.fullName } shouldBe listOf("my-org/my-repo")
                    }
                }

                context("org의 백필이 끝난 경우") {
                    it("로컬 projection에서 조회한다") {
                        every { backfillStatusRepository.existsById("my-org") } returns true
                        every { repoProjectionRepository.findByOwnerAndDeletedFalse("my-org") } returns listOf(
                            GithubRepoProjection(
                                id = "my-org/my-repo",
                                installationId = 1L,
                                owner = "my-org",
                                repo = "my-repo",
                                fullName = "my-org/my-repo",
                                private = true,
                                htmlUrl = "https://github.com/my-org/my-repo",
                                sourceOccurredAt = Instant.EPOCH,
                            ),
                        )

                        val result = reader.listOrgRepos("my-org")

                        result.map { it.fullName } shouldBe listOf("my-org/my-repo")
                    }
                }
            }
        }
    })
