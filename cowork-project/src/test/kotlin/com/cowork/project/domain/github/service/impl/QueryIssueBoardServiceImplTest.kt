package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class QueryIssueBoardServiceImplTest :
    DescribeSpec({

        lateinit var repoAccessResolver: GithubRepoAccessResolver
        lateinit var callExecutor: GithubAppCallExecutor
        lateinit var githubAppClient: GithubAppClient
        lateinit var service: QueryIssueBoardServiceImpl

        beforeEach {
            repoAccessResolver = mockk()
            callExecutor = mockk()
            githubAppClient = mockk()
            service = QueryIssueBoardServiceImpl(repoAccessResolver, callExecutor, githubAppClient)

            every { callExecutor.execute(any<() -> List<GithubIssueResDto>>()) } answers {
                firstArg<() -> List<GithubIssueResDto>>().invoke()
            }
        }

        fun issue(number: Int) = GithubIssueResDto(
            number = number,
            title = "issue-$number",
            author = "octocat",
            state = "open",
            htmlUrl = "https://github.com/my-org/my-repo/issues/$number",
            labels = listOf("bug"),
            createdAt = "2026-06-23T00:00:00Z",
            updatedAt = "2026-06-23T00:00:00Z",
        )

        describe("QueryIssueBoardServiceImpl 클래스의") {
            describe("execute 메서드는") {
                context("읽기 권한이 있는 경우") {
                    it("연결된 레포의 열린 이슈를 조회한다") {
                        every { repoAccessResolver.resolveForRead(7L, 1L, 5L) } returns GithubRepoRef("my-org", "my-repo")
                        every { githubAppClient.listIssues("my-org", "my-repo", "open") } returns
                            listOf(issue(1), issue(2))

                        val result = service.execute(7L, 1L, 5L)

                        result.map { it.number } shouldBe listOf(1, 2)
                    }
                }
            }
        }
    })
