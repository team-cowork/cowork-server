package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestSummaryResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class GetPullRequestBoardServiceImplTest : DescribeSpec({

    lateinit var repoAccessResolver: GithubRepoAccessResolver
    lateinit var callExecutor: GithubAppCallExecutor
    lateinit var githubAppClient: GithubAppClient
    lateinit var service: GetPullRequestBoardServiceImpl

    beforeEach {
        repoAccessResolver = mockk()
        callExecutor = mockk()
        githubAppClient = mockk()
        service = GetPullRequestBoardServiceImpl(repoAccessResolver, callExecutor, githubAppClient)

        every { callExecutor.execute(any<() -> List<GithubPullRequestSummaryResDto>>()) } answers {
            firstArg<() -> List<GithubPullRequestSummaryResDto>>().invoke()
        }
    }

    fun summary(number: Int, draft: Boolean) =
        GithubPullRequestSummaryResDto(
            number = number,
            title = "pr-$number",
            author = "octocat",
            state = "open",
            draft = draft,
            merged = false,
            htmlUrl = "https://github.com/my-org/my-repo/pull/$number",
            labels = emptyList(),
            createdAt = "2026-06-23T00:00:00Z",
            updatedAt = "2026-06-23T00:00:00Z",
        )

    describe("GetPullRequestBoardServiceImpl 클래스의") {
        describe("getPullRequestBoard 메서드는") {
            context("읽기 권한이 있는 경우") {
                it("열린 PR을 draft/inReview 컬럼으로 분리한다") {
                    every { repoAccessResolver.resolveForRead(7L, 1L) } returns GithubRepoRef("my-org", "my-repo")
                    every { githubAppClient.listPullRequests("my-org", "my-repo", "open") } returns
                        listOf(summary(1, draft = true), summary(2, draft = false), summary(3, draft = false))

                    val board = service.getPullRequestBoard(7L, 1L)

                    board.draft.map { it.number } shouldBe listOf(1)
                    board.inReview.map { it.number } shouldBe listOf(2, 3)
                }
            }
        }
    }
})
