package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationResDto
import com.cowork.project.domain.githubBoard.repository.GithubProjectItemWriteOperationRepository
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardOperationService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class QueryGithubBoardOperationServiceImpl(
    private val accessResolver: GithubBoardAccessResolver,
    private val operationRepository: GithubProjectItemWriteOperationRepository,
) : QueryGithubBoardOperationService {

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        operationId: String,
    ): GithubBoardOperationResDto {
        val link = accessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.READ)
        val operation = operationRepository.findByOperationIdAndBoardId(operationId, link.id)
            ?: throw ExpectedException("카드 이동 작업을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return GithubBoardOperationResDto.of(operation)
    }
}
