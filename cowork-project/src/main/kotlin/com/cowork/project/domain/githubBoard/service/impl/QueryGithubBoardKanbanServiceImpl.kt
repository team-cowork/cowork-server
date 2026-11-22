package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.model.GithubBoardItemQuery
import com.cowork.project.domain.githubBoard.model.GithubBoardKanbanBuilder
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardColumnFieldResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardColumnResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardItemResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardKanbanResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardViewResDto
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.GithubBoardItemFilter
import com.cowork.project.domain.githubBoard.service.GithubBoardReader
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardKanbanService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryGithubBoardKanbanServiceImpl(
    private val githubBoardAccessResolver: GithubBoardAccessResolver,
    private val githubBoardReader: GithubBoardReader,
    private val githubBoardItemFilter: GithubBoardItemFilter,
) : QueryGithubBoardKanbanService {

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        viewNumber: Int,
        query: GithubBoardItemQuery,
    ): GithubBoardKanbanResDto {
        val link = githubBoardAccessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.READ)
        val board = githubBoardReader.read(link)
        val view = GithubBoardKanbanBuilder.requireBoardView(board, viewNumber)
        val result = githubBoardItemFilter.apply(board, view, query, userId)
        val kanban = GithubBoardKanbanBuilder.build(board, view, result.items)
        return GithubBoardKanbanResDto(
            view = GithubBoardViewResDto.of(view),
            columnField = GithubBoardColumnFieldResDto(
                kanban.columnField.id,
                kanban.columnField.name,
                kanban.columnField.dataType,
            ),
            columns = kanban.columns.map { column ->
                GithubBoardColumnResDto(
                    id = column.id,
                    name = column.name,
                    color = column.color,
                    itemCount = column.items.size,
                    items = column.items.map { GithubBoardItemResDto.of(it, board) },
                )
            },
            appliedFilter = result.appliedFilter,
            unsupportedFilterTokens = result.unsupportedTokens,
        )
    }
}
