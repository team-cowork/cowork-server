package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/**
 * 프로젝트에 등록된 GitHub 레포지토리 참조를 조회 권한(팀 멤버) 또는
 * 수정 권한(프로젝트 수정자) 기준으로 해석한다.
 */
@Component
class GithubRepoAccessResolver(
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
) {

    fun resolveForRead(userId: Long, projectId: Long, repoId: Long): GithubRepoRef {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireTeamMember(project.teamId, userId)
        return parseLinkedRepo(projectId, repoId)
    }

    fun resolveForModify(userId: Long, projectId: Long, repoId: Long): GithubRepoRef {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)
        return parseLinkedRepo(projectId, repoId)
    }

    fun resolveForModifyForUpdate(userId: Long, projectId: Long, repoId: Long): GithubRepoRef {
        val project = projectAccessGuard.findProjectForUpdateOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)
        val repoLink = projectGithubRepoRepository.findByIdAndProjectIdForUpdate(repoId, projectId)
            ?: throw ExpectedException("등록된 레포를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return parseLinkedRepoUrl(repoLink.githubRepoUrl)
    }

    fun requireStateMutationAccess(userId: Long, projectId: Long) {
        val project = projectAccessGuard.findProjectForUpdateOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)
    }

    private fun parseLinkedRepo(projectId: Long, repoId: Long): GithubRepoRef {
        val repoLink = projectGithubRepoRepository.findByIdAndProjectId(repoId, projectId)
            ?: throw ExpectedException("등록된 레포를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return parseLinkedRepoUrl(repoLink.githubRepoUrl)
    }

    private fun parseLinkedRepoUrl(githubRepoUrl: String): GithubRepoRef = GithubRepoUrlParser.parse(githubRepoUrl)
        ?: throw ExpectedException(
            "연결된 GitHub 레포지토리 URL이 올바르지 않습니다.",
            HttpStatus.BAD_REQUEST,
        )
}
