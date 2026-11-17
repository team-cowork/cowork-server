package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.presentation.data.response.ProjectGithubBoardResDto
import com.cowork.project.domain.githubBoard.repository.GithubProjectProjectionRepository
import com.cowork.project.domain.githubBoard.repository.ProjectGithubBoardRepository
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.QueryProjectGithubBoardsService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryProjectGithubBoardsServiceImpl(
    private val projectAccessGuard: ProjectAccessGuard,
    private val accessResolver: GithubBoardAccessResolver,
    private val boardRepository: ProjectGithubBoardRepository,
    private val projectionRepository: GithubProjectProjectionRepository,
) : QueryProjectGithubBoardsService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long): List<ProjectGithubBoardResDto> {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        accessResolver.requireBoardAccess(project, userId, GithubBoardAccess.READ)
        val boards = boardRepository.findAllByProjectIdOrderByIdAsc(projectId)
        val projections = projectionRepository.findAllById(boards.map { it.projectionId }).associateBy { it.id }
        return boards.map { ProjectGithubBoardResDto.of(it, projections[it.projectionId]) }
    }
}
