package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.model.GithubBoardItemQuery
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardItemResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardItemsResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardViewResDto
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.GithubBoardItemFilter
import com.cowork.project.domain.githubBoard.service.GithubBoardReader
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardItemsService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class QueryGithubBoardItemsServiceImpl(
    private val githubBoardAccessResolver: GithubBoardAccessResolver,
    private val githubBoardReader: GithubBoardReader,
    private val githubBoardItemFilter: GithubBoardItemFilter,
) : QueryGithubBoardItemsService {

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        viewNumber: Int,
        query: GithubBoardItemQuery,
    ): GithubBoardItemsResDto {
        val link = githubBoardAccessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.READ)
        val board = githubBoardReader.read(link)
        val view =
            board.view(viewNumber) ?: throw ExpectedException("GitHub Project 뷰를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        val result = githubBoardItemFilter.apply(board, view, query, userId)
        return GithubBoardItemsResDto(
            view = GithubBoardViewResDto.of(view),
            items = result.items.map { GithubBoardItemResDto.of(it, board) },
            appliedFilter = result.appliedFilter,
            unsupportedFilterTokens = result.unsupportedTokens,
        )
    }
}
