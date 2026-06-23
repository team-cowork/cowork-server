package com.cowork.project.github

import com.cowork.project.client.UserClient
import com.cowork.project.client.UserProfileResDto
import com.cowork.project.domain.Project
import com.cowork.project.dto.GithubMergeResultResDto
import com.cowork.project.dto.GithubPullRequestResDto
import com.cowork.project.service.ProjectAccessGuard
import feign.FeignException
import feign.Request
import feign.RequestTemplate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubPullRequestServiceTest : DescribeSpec({

    lateinit var projectAccessGuard: ProjectAccessGuard
    lateinit var userClient: UserClient
    lateinit var githubAppClient: GithubAppClient
    lateinit var service: GithubPullRequestService

    beforeEach {
        projectAccessGuard = mockk()
        userClient = mockk()
        githubAppClient = mockk()
        service = GithubPullRequestService(projectAccessGuard, userClient, githubAppClient)
    }

    fun project(githubRepoUrl: String? = "https://github.com/my-org/my-repo") =
        Project(id = 1L, teamId = 100L, name = "p", description = null, createdBy = 1L, githubRepoUrl = githubRepoUrl)

    describe("GithubPullRequestService 클래스의") {
        describe("getPullRequestDetail 메서드는") {
            context("팀 멤버인 경우") {
                it("githubAppClient를 호출해 PR 상세를 반환한다") {
                    val proj = project()
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                    every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs
                    val expected = mockk<GithubPullRequestResDto>()
                    every { githubAppClient.getPullRequest("my-org", "my-repo", 5) } returns expected

                    val result = service.getPullRequestDetail(7L, 1L, 5)

                    result shouldBe expected
                }
            }

            context("프로젝트에 연결된 레포가 없는 경우") {
                it("BAD_REQUEST를 던진다") {
                    val proj = project(githubRepoUrl = null)
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                    every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs

                    val ex = shouldThrow<ExpectedException> { service.getPullRequestDetail(7L, 1L, 5) }

                    ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("githubAppClient 호출 중 네트워크 오류가 발생하는 경우") {
                it("BAD_GATEWAY로 변환한다") {
                    val proj = project()
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                    every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs
                    val networkError = FeignException.NotFound(
                        "connect timed out",
                        Request.create(Request.HttpMethod.GET, "/api/repos/my-org/my-repo/pulls/5", emptyMap(), null, RequestTemplate()),
                        null,
                        emptyMap(),
                    )
                    every { githubAppClient.getPullRequest("my-org", "my-repo", 5) } throws networkError

                    val ex = shouldThrow<ExpectedException> { service.getPullRequestDetail(7L, 1L, 5) }

                    ex.statusCode shouldBe HttpStatus.BAD_GATEWAY
                }
            }
        }

        describe("mergePullRequest 메서드는") {
            context("requireProjectModifier를 통과하는 경우") {
                it("githubUsername을 조회해 머지를 요청한다") {
                    val proj = project()
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                    every { projectAccessGuard.requireProjectModifier(proj, 7L) } just Runs
                    every { userClient.getUserProfile(7L) } returns UserProfileResDto(githubId = "octocat")
                    val expected = mockk<GithubMergeResultResDto>()
                    every {
                        githubAppClient.mergePullRequest("my-org", "my-repo", 5, mapOf("requesterGithubUsername" to "octocat"))
                    } returns expected

                    val result = service.mergePullRequest(7L, 1L, 5)

                    result shouldBe expected
                }
            }

            context("githubId가 null인 경우") {
                it("githubAppClient를 호출하지 않고 BAD_REQUEST를 던진다") {
                    val proj = project()
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                    every { projectAccessGuard.requireProjectModifier(proj, 7L) } just Runs
                    every { userClient.getUserProfile(7L) } returns UserProfileResDto(githubId = null)

                    val ex = shouldThrow<ExpectedException> { service.mergePullRequest(7L, 1L, 5) }

                    ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                    verify(exactly = 0) { githubAppClient.mergePullRequest(any(), any(), any(), any()) }
                }
            }

            context("cowork-user가 404를 반환하는 경우") {
                it("BAD_REQUEST로 변환한다") {
                    val proj = project()
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                    every { projectAccessGuard.requireProjectModifier(proj, 7L) } just Runs
                    val notFound = FeignException.NotFound(
                        "not found",
                        Request.create(Request.HttpMethod.GET, "/users/7", emptyMap(), null, RequestTemplate()),
                        null,
                        emptyMap(),
                    )
                    every { userClient.getUserProfile(7L) } throws notFound

                    val ex = shouldThrow<ExpectedException> { service.mergePullRequest(7L, 1L, 5) }

                    ex.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            context("cowork-user 호출 중 예기치 못한 예외가 발생하는 경우") {
                it("BAD_GATEWAY로 변환한다") {
                    val proj = project()
                    every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
                    every { projectAccessGuard.requireProjectModifier(proj, 7L) } just Runs
                    every { userClient.getUserProfile(7L) } throws IllegalStateException("No instances available for cowork-user")

                    val ex = shouldThrow<ExpectedException> { service.mergePullRequest(7L, 1L, 5) }

                    ex.statusCode shouldBe HttpStatus.BAD_GATEWAY
                }
            }
        }
    }
})
