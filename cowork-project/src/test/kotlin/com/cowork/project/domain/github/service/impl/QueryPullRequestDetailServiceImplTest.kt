package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubPullRequestLiveStatus
import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import com.cowork.project.domain.github.repository.GithubPullRequestProjectionRepository
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubPullRequestReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubSyncMarkers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

class QueryPullRequestDetailServiceImplTest :
    DescribeSpec({

        lateinit var pullRequestRepository: GithubPullRequestProjectionRepository
        lateinit var markerRepository: GithubSyncMarkerRepository
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: QueryPullRequestDetailServiceImpl

        beforeEach {
            pullRequestRepository = mockk()
            markerRepository = mockk()
            githubAppClient = mockk()
            val repoAccessResolver = mockk<GithubRepoAccessResolver>()
            every { repoAccessResolver.resolveForRead(1L, 2L, 3L) } returns GithubRepoRef("my-org", "my-repo")
            val callExecutor = mockk<GithubAppCallExecutor>()
            every { callExecutor.execute(any<() -> GithubPullRequestLiveStatus>()) } answers {
                firstArg<() -> GithubPullRequestLiveStatus>().invoke()
            }
            service = QueryPullRequestDetailServiceImpl(
                repoAccessResolver,
                GithubPullRequestReader(pullRequestRepository, GithubSyncMarkers(markerRepository)),
                callExecutor,
                githubAppClient,
            )
        }

        describe("QueryPullRequestDetailServiceImpl 클래스의 execute 메서드는") {
            context("동기화가 끝났고 PR이 projection에 있는 경우") {
                it("projection 필드에 live 상태 세 필드를 합쳐 반환한다") {
                    every { markerRepository.existsById("PULL_REQUEST:my-org/my-repo") } returns true
                    every { pullRequestRepository.findByOwnerAndRepoAndNumber("my-org", "my-repo", 7) } returns
                        GithubPullRequestProjection(
                            id = "my-org/my-repo#7",
                            owner = "my-org",
                            repo = "my-repo",
                            number = 7,
                            title = "Add feature",
                            body = "본문",
                            author = "octocat",
                            state = "open",
                            draft = false,
                            merged = false,
                            htmlUrl = "https://github.com/my-org/my-repo/pull/7",
                            headRef = "feature",
                            baseRef = "main",
                            githubCreatedAt = "2026-01-01T00:00:00Z",
                            githubUpdatedAt = "2026-01-02T00:00:00Z",
                            sourceOccurredAt = Instant.EPOCH,
                        )
                    every { githubAppClient.getPullRequestLiveStatus("my-org", "my-repo", 7) } returns
                        GithubPullRequestLiveStatus(
                            mergeable = true,
                            mergeableState = "clean",
                            reviewDecision = "APPROVED",
                        )

                    val result = service.execute(1L, 2L, 3L, 7)

                    result.title shouldBe "Add feature"
                    result.headRef shouldBe "feature"
                    result.mergeable shouldBe true
                    result.mergeableState shouldBe "clean"
                    result.reviewDecision shouldBe "APPROVED"
                }
            }

            context("repo의 PR 동기화가 끝나지 않은 경우") {
                it("503을 던지고 GitHub을 호출하지 않는다") {
                    every { markerRepository.existsById("PULL_REQUEST:my-org/my-repo") } returns false

                    val ex = shouldThrow<ExpectedException> { service.execute(1L, 2L, 3L, 7) }

                    ex.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                    verify(exactly = 0) { githubAppClient.getPullRequestLiveStatus(any(), any(), any()) }
                }
            }
        }
    })
