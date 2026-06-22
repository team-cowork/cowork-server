package com.cowork.project.github

import com.cowork.project.client.UserClient
import com.cowork.project.client.UserProfileResponse
import com.cowork.project.domain.Project
import com.cowork.project.dto.GithubMergeResultResponse
import com.cowork.project.dto.GithubPullRequestResponse
import com.cowork.project.service.ProjectAccessGuard
import feign.FeignException
import feign.Request
import feign.RequestTemplate
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GithubPullRequestServiceTest {

    private val projectAccessGuard = mockk<ProjectAccessGuard>()
    private val userClient = mockk<UserClient>()
    private val githubAppClient = mockk<GithubAppClient>()

    private val service = GithubPullRequestService(projectAccessGuard, userClient, githubAppClient)

    private fun project(githubRepoUrl: String? = "https://github.com/my-org/my-repo") =
        Project(id = 1L, teamId = 100L, name = "p", description = null, createdBy = 1L, githubRepoUrl = githubRepoUrl)

    @Test
    fun `getPullRequestDetail은 팀 멤버면 githubAppClient를 호출`() {
        val proj = project()
        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
        every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs
        val expected = mockk<GithubPullRequestResponse>()
        every { githubAppClient.getPullRequest("my-org", "my-repo", 5) } returns expected

        val result = service.getPullRequestDetail(7L, 1L, 5)

        assertEquals(expected, result)
    }

    @Test
    fun `프로젝트에 연결된 레포가 없으면 BAD_REQUEST`() {
        val proj = project(githubRepoUrl = null)
        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
        every { projectAccessGuard.requireTeamMember(100L, 7L) } just Runs

        val ex = assertThrows(ExpectedException::class.java) { service.getPullRequestDetail(7L, 1L, 5) }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `mergePullRequest는 requireProjectModifier를 통과해야 githubUsername을 조회`() {
        val proj = project()
        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
        every { projectAccessGuard.requireProjectModifier(proj, 7L) } just Runs
        every { userClient.getUserProfile(7L) } returns UserProfileResponse(githubId = "octocat")
        val expected = mockk<GithubMergeResultResponse>()
        every { githubAppClient.mergePullRequest("my-org", "my-repo", 5, "octocat") } returns expected

        val result = service.mergePullRequest(7L, 1L, 5)

        assertEquals(expected, result)
    }

    @Test
    fun `githubId가 null이면 githubAppClient를 호출하지 않고 BAD_REQUEST`() {
        val proj = project()
        every { projectAccessGuard.findProjectOrThrow(1L) } returns proj
        every { projectAccessGuard.requireProjectModifier(proj, 7L) } just Runs
        every { userClient.getUserProfile(7L) } returns UserProfileResponse(githubId = null)

        val ex = assertThrows(ExpectedException::class.java) { service.mergePullRequest(7L, 1L, 5) }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { githubAppClient.mergePullRequest(any(), any(), any(), any()) }
    }

    @Test
    fun `cowork-user가 404면 BAD_REQUEST로 변환`() {
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

        val ex = assertThrows(ExpectedException::class.java) { service.mergePullRequest(7L, 1L, 5) }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
