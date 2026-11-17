package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.repository.ProjectGithubBoardRepository
import com.cowork.project.domain.githubBoard.service.RemoveProjectGithubBoardService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class RemoveProjectGithubBoardServiceImpl(
    private val projectAccessGuard: ProjectAccessGuard,
    private val boardRepository: ProjectGithubBoardRepository,
) : RemoveProjectGithubBoardService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, boardId: Long) {
        val project = projectAccessGuard.findProjectForUpdateOrThrow(projectId)
        projectAccessGuard.requireGithubManager(project, userId)
        val board = boardRepository.findByIdAndProjectId(boardId, projectId)
            ?: throw ExpectedException("연결된 GitHub Project를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        // 같은 GitHub Project를 다른 cowork 프로젝트가 연결했을 수 있어 projection은 지우지 않는다.
        boardRepository.delete(board)
    }
}
