package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

/**
 * 프로젝트에 등록된 GitHub 레포지토리 참조를 조회 권한(팀 멤버) 또는
 * 수정 권한(프로젝트 수정자) 기준으로 해석한다.
 *
 * 읽기용 해석은 자체 읽기 트랜잭션을 가진다. 트랜잭션 없이 호출한 서비스(cowork-github 호출·Kafka 발행)는
 * 권한 확인이 끝나면 커넥션을 반납하고 불변 값 [GithubRepoRef]만 들고 외부 호출로 넘어간다.
 */
@Component
class GithubRepoAccessResolver(
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val teamGithubInstallationRepository: TeamGithubInstallationRepository,
) {

    @Transactional(readOnly = true)
    fun resolveForRead(userId: Long, projectId: Long, repoId: Long): GithubRepoRef {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectReader(project, userId)
        return parseLinkedRepo(project.teamId, projectId, repoId)
    }

    @Transactional(readOnly = true)
    fun resolveForModify(userId: Long, projectId: Long, repoId: Long): GithubRepoRef {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)
        return parseLinkedRepo(project.teamId, projectId, repoId)
    }

    fun resolveForManageForUpdate(userId: Long, projectId: Long, repoId: Long): GithubRepoRef {
        val project = projectAccessGuard.findProjectForUpdateOrThrow(projectId)
        projectAccessGuard.requireGithubManager(project, userId)
        val repoLink = projectGithubRepoRepository.findByIdAndProjectIdForUpdate(repoId, projectId)
            ?: throw ExpectedException("등록된 레포를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return parseLinkedRepoUrl(project.teamId, repoLink.githubRepoUrl)
    }

    private fun parseLinkedRepo(teamId: Long, projectId: Long, repoId: Long): GithubRepoRef {
        val repoLink = projectGithubRepoRepository.findByIdAndProjectId(repoId, projectId)
            ?: throw ExpectedException("등록된 레포를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return parseLinkedRepoUrl(teamId, repoLink.githubRepoUrl)
    }

    /**
     * cowork-github는 설치 토큰을 owner 이름으로 고르므로, 팀에 연결된 조직이 아닌 레포를 허용하면
     * 다른 팀 조직의 토큰으로 그 레포를 읽고 쓰게 된다.
     */
    fun requireTeamOrgRepo(teamId: Long, ref: GithubRepoRef) {
        val orgLogin = teamGithubInstallationRepository.findByIdOrNull(teamId)?.orgLogin
        if (!ref.owner.equals(orgLogin, ignoreCase = true)) {
            throw ExpectedException("팀에 연결된 GitHub 조직의 레포지토리만 사용할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }

    private fun parseLinkedRepoUrl(teamId: Long, githubRepoUrl: String): GithubRepoRef {
        val ref = GithubRepoUrlParser.parse(githubRepoUrl)
            ?: throw ExpectedException(
                "연결된 GitHub 레포지토리 URL이 올바르지 않습니다.",
                HttpStatus.BAD_REQUEST,
            )
        requireTeamOrgRepo(teamId, ref)
        return ref
    }
}
