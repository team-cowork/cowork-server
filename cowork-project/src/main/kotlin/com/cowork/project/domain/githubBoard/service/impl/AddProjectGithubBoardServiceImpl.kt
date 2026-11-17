package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.github.service.GithubSyncRequestScheduler
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import com.cowork.project.domain.githubBoard.presentation.data.request.AddProjectGithubBoardReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.ProjectGithubBoardResDto
import com.cowork.project.domain.githubBoard.repository.GithubProjectProjectionRepository
import com.cowork.project.domain.githubBoard.repository.ProjectGithubBoardRepository
import com.cowork.project.domain.githubBoard.service.AddProjectGithubBoardService
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.GithubBoardUrlParser
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class AddProjectGithubBoardServiceImpl(
    private val projectAccessGuard: ProjectAccessGuard,
    private val accessResolver: GithubBoardAccessResolver,
    private val boardRepository: ProjectGithubBoardRepository,
    private val projectionRepository: GithubProjectProjectionRepository,
    private val syncScheduler: GithubSyncRequestScheduler,
) : AddProjectGithubBoardService {

    @Transactional
    override fun execute(
        userId: Long,
        projectId: Long,
        request: AddProjectGithubBoardReqDto,
    ): ProjectGithubBoardResDto {
        val project = projectAccessGuard.findProjectForUpdateOrThrow(projectId)
        projectAccessGuard.requireGithubManager(project, userId)
        val ref = GithubBoardUrlParser.parse(request.url)
        accessResolver.requireTeamOrg(project.teamId, ref.owner)
        if (boardRepository.existsByProjectIdAndOwnerLoginAndProjectNumber(projectId, ref.owner, ref.projectNumber)) {
            throw duplicated()
        }
        val board = ProjectGithubBoard(
            projectId = projectId,
            teamId = project.teamId,
            ownerLogin = ref.owner,
            projectNumber = ref.projectNumber,
            createdBy = userId,
        )
        // 동시에 같은 Project를 연결하면 위 검사만으로는 막지 못하므로 유니크 제약 위반을 409로 바꾼다.
        val saved = try {
            boardRepository.saveAndFlush(board)
        } catch (_: DataIntegrityViolationException) {
            throw duplicated()
        }
        syncScheduler.requestProjectSync(ref.owner, ref.projectNumber, force = false)
        return ProjectGithubBoardResDto.of(saved, projectionRepository.findById(saved.projectionId).orElse(null))
    }

    private fun duplicated() = ExpectedException("이미 이 프로젝트에 연결된 GitHub Project입니다.", HttpStatus.CONFLICT)
}
