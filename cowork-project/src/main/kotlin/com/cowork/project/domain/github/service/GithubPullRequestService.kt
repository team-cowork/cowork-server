package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient

import com.cowork.project.domain.client.UserClient
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.github.presentation.data.response.GithubApproveResultResDto
import com.cowork.project.domain.github.presentation.data.response.GithubMergeResultResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestBoardResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.project.service.ProjectAccessGuard
import feign.FeignException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional(readOnly = true)
class GithubPullRequestService(
    private val projectAccessGuard: ProjectAccessGuard,
    private val userClient: UserClient,
    private val githubAppClient: GithubAppClient,
) {
    private val logger = LoggerFactory.getLogger(GithubPullRequestService::class.java)

    fun getPullRequestBoard(userId: Long, projectId: Long): GithubPullRequestBoardResDto {
        val repo = resolveRepoRefForRead(userId, projectId)
        val pulls = callGithubApp { githubAppClient.listPullRequests(repo.owner, repo.repo, "open") }
        val (draft, inReview) = pulls.partition { it.draft }
        return GithubPullRequestBoardResDto(draft = draft, inReview = inReview)
    }

    fun getPullRequestDetail(userId: Long, projectId: Long, prNumber: Int): GithubPullRequestResDto {
        val repo = resolveRepoRefForRead(userId, projectId)
        return callGithubApp { githubAppClient.getPullRequest(repo.owner, repo.repo, prNumber) }
    }

    fun listPullRequestFiles(userId: Long, projectId: Long, prNumber: Int): List<GithubPullRequestFileResDto> {
        val repo = resolveRepoRefForRead(userId, projectId)
        return callGithubApp { githubAppClient.listPullRequestFiles(repo.owner, repo.repo, prNumber) }
    }

    fun mergePullRequest(userId: Long, projectId: Long, prNumber: Int): GithubMergeResultResDto {
        val repo = resolveRepoRefForModify(userId, projectId)
        val githubUsername = resolveGithubUsername(userId)
        return callGithubApp {
            githubAppClient.mergePullRequest(repo.owner, repo.repo, prNumber, mapOf("requesterGithubUsername" to githubUsername))
        }
    }

    fun approvePullRequest(userId: Long, projectId: Long, prNumber: Int): GithubApproveResultResDto {
        val repo = resolveRepoRefForModify(userId, projectId)
        val githubUsername = resolveGithubUsername(userId)
        return callGithubApp {
            githubAppClient.approvePullRequest(repo.owner, repo.repo, prNumber, mapOf("requesterGithubUsername" to githubUsername))
        }
    }

    private fun resolveRepoRefForRead(userId: Long, projectId: Long): GithubRepoRef {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireTeamMember(project.teamId, userId)
        return parseLinkedRepo(project)
    }

    private fun resolveRepoRefForModify(userId: Long, projectId: Long): GithubRepoRef {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)
        return parseLinkedRepo(project)
    }

    private fun parseLinkedRepo(project: Project): GithubRepoRef {
        val url = project.githubRepoUrl
            ?: throw ExpectedException("연결된 GitHub 레포지토리가 없습니다.", HttpStatus.BAD_REQUEST)
        return GithubRepoUrlParser.parse(url)
            ?: throw ExpectedException("연결된 GitHub 레포지토리 URL이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
    }

    private fun <T> callGithubApp(call: () -> T): T =
        try {
            call()
        } catch (e: FeignException) {
            logger.error("Failed to call cowork-github-app due to network or timeout error", e)
            throw ExpectedException("GitHub 연동 서버와 통신할 수 없습니다.", HttpStatus.BAD_GATEWAY)
        }

    private fun resolveGithubUsername(userId: Long): String {
        val profile = try {
            userClient.getUserProfile(userId)
        } catch (e: FeignException.NotFound) {
            throw ExpectedException("사용자 정보를 찾을 수 없습니다.", HttpStatus.BAD_REQUEST)
        } catch (e: FeignException) {
            logger.error("Failed to call cowork-user for userId {}", userId, e)
            throw ExpectedException("사용자 정보 조회에 실패했습니다.", HttpStatus.BAD_GATEWAY)
        } catch (e: Exception) {
            logger.error("Unexpected error while calling cowork-user for userId {}", userId, e)
            throw ExpectedException("사용자 정보 조회에 실패했습니다.", HttpStatus.BAD_GATEWAY)
        }

        return profile.githubId
            ?: throw ExpectedException("GitHub 계정이 연동되어 있지 않습니다. 계정 설정에서 GitHub 계정을 연동해주세요.", HttpStatus.BAD_REQUEST)
    }
}
