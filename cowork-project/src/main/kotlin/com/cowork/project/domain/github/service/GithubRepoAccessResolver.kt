package com.cowork.project.domain.github.service

import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/**
 * 프로젝트에 연결된 GitHub 레포지토리 참조를 조회 권한(팀 멤버) 또는
 * 수정 권한(프로젝트 수정자) 기준으로 해석한다.
 */
@Component
class GithubRepoAccessResolver(
    private val projectAccessGuard: ProjectAccessGuard,
) {

    fun resolveForRead(userId: Long, projectId: Long): GithubRepoRef {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireTeamMember(project.teamId, userId)
        return parseLinkedRepo(project)
    }

    fun resolveForModify(userId: Long, projectId: Long): GithubRepoRef {
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
}
