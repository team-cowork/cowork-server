package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import com.cowork.project.domain.githubBoard.repository.ProjectGithubBoardRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

enum class GithubBoardAccess { READ, WRITE }

/** 프로젝트에 연결한 GitHub 보드를 권한과 함께 찾는다. 보기는 보드 보기 권한, 카드 이동은 보드 수정 권한이 필요하다. */
@Component
class GithubBoardAccessResolver(
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectGithubBoardRepository: ProjectGithubBoardRepository,
    private val teamGithubInstallationRepository: TeamGithubInstallationRepository,
) {
    fun resolve(userId: Long, projectId: Long, boardId: Long, access: GithubBoardAccess): ProjectGithubBoard {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        requireBoardAccess(project, userId, access)
        val board = projectGithubBoardRepository.findByIdAndProjectId(boardId, projectId)
            ?: throw ExpectedException("연결된 GitHub Project를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        requireTeamOrg(project.teamId, board.ownerLogin)
        return board
    }

    fun requireBoardAccess(project: Project, userId: Long, access: GithubBoardAccess) {
        when (access) {
            GithubBoardAccess.READ -> projectAccessGuard.requireBoardReader(project, userId)
            GithubBoardAccess.WRITE -> projectAccessGuard.requireBoardModifier(project, userId)
        }
    }

    /**
     * `cowork-github`는 설치 토큰을 조직 login으로 고르므로, 팀에 연결된 조직이 아닌 Project를 허용하면
     * 다른 팀 조직의 토큰으로 그 Project를 읽고 쓰게 된다.
     */
    fun requireTeamOrg(teamId: Long, owner: String) {
        val orgLogin = teamGithubInstallationRepository.findByIdOrNull(teamId)?.orgLogin
        if (!owner.equals(orgLogin, ignoreCase = true)) {
            throw ExpectedException("팀에 연결된 GitHub 조직의 Project만 사용할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
