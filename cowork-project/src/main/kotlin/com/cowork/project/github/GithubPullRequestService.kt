package com.cowork.project.github

import com.cowork.project.client.UserClient
import com.cowork.project.domain.Project
import com.cowork.project.dto.GithubApproveResultResponse
import com.cowork.project.dto.GithubMergeResultResponse
import com.cowork.project.dto.GithubPullRequestFileResponse
import com.cowork.project.dto.GithubPullRequestResponse
import com.cowork.project.service.ProjectAccessGuard
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

    fun getPullRequestDetail(userId: Long, projectId: Long, prNumber: Int): GithubPullRequestResponse {
        val repo = resolveRepoRef(userId, projectId, requireModifier = false)
        return githubAppClient.getPullRequest(repo.owner, repo.repo, prNumber)
    }

    fun listPullRequestFiles(userId: Long, projectId: Long, prNumber: Int): List<GithubPullRequestFileResponse> {
        val repo = resolveRepoRef(userId, projectId, requireModifier = false)
        return githubAppClient.listPullRequestFiles(repo.owner, repo.repo, prNumber)
    }

    fun mergePullRequest(userId: Long, projectId: Long, prNumber: Int): GithubMergeResultResponse {
        val repo = resolveRepoRef(userId, projectId, requireModifier = true)
        val githubUsername = resolveGithubUsername(userId)
        return githubAppClient.mergePullRequest(repo.owner, repo.repo, prNumber, githubUsername)
    }

    fun approvePullRequest(userId: Long, projectId: Long, prNumber: Int): GithubApproveResultResponse {
        val repo = resolveRepoRef(userId, projectId, requireModifier = true)
        val githubUsername = resolveGithubUsername(userId)
        return githubAppClient.approvePullRequest(repo.owner, repo.repo, prNumber, githubUsername)
    }

    private fun resolveRepoRef(userId: Long, projectId: Long, requireModifier: Boolean): GithubRepoRef {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        if (requireModifier) {
            projectAccessGuard.requireProjectModifier(project, userId)
        } else {
            projectAccessGuard.requireTeamMember(project.teamId, userId)
        }
        return parseLinkedRepo(project)
    }

    private fun parseLinkedRepo(project: Project): GithubRepoRef {
        val url = project.githubRepoUrl
            ?: throw ExpectedException("연결된 GitHub 레포지토리가 없습니다.", HttpStatus.BAD_REQUEST)
        return GithubRepoUrlParser.parse(url)
            ?: throw ExpectedException("연결된 GitHub 레포지토리 URL이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
    }

    private fun resolveGithubUsername(userId: Long): String {
        val profile = try {
            userClient.getUserProfile(userId)
        } catch (e: FeignException.NotFound) {
            throw ExpectedException("사용자 정보를 찾을 수 없습니다.", HttpStatus.BAD_REQUEST)
        } catch (e: FeignException) {
            logger.error("cowork-user 호출 실패 userId=$userId", e)
            throw ExpectedException("사용자 정보 조회에 실패했습니다.", HttpStatus.BAD_GATEWAY)
        }

        return profile.githubId
            ?: throw ExpectedException("GitHub 계정이 연동되어 있지 않습니다. 계정 설정에서 GitHub 계정을 연동해주세요.", HttpStatus.BAD_REQUEST)
    }
}
