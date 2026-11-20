package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.model.GithubBoardItemQuery
import com.cowork.project.domain.githubBoard.model.GithubBoardTableBuilder
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardFieldResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardGroupResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardItemResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardSortResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardTableResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardViewResDto
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.GithubBoardItemFilter
import com.cowork.project.domain.githubBoard.service.GithubBoardReader
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardTableService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryGithubBoardTableServiceImpl(
    private val githubBoardAccessResolver: GithubBoardAccessResolver,
    private val githubBoardReader: GithubBoardReader,
    private val githubBoardItemFilter: GithubBoardItemFilter,
) : QueryGithubBoardTableService {

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        viewNumber: Int,
        query: GithubBoardItemQuery,
    ): GithubBoardTableResDto {
        val link = githubBoardAccessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.READ)
        val board = githubBoardReader.read(link)
        val view = GithubBoardTableBuilder.requireTableView(board, viewNumber)
        val result = githubBoardItemFilter.apply(board, view, query, userId)
        val table = GithubBoardTableBuilder.build(board, view, result.items)
        return GithubBoardTableResDto(
            view = GithubBoardViewResDto.of(view),
            visibleFields = table.visibleFields.map { GithubBoardFieldResDto.of(it) },
            sortBy = table.sortBy.map { GithubBoardSortResDto(GithubBoardFieldResDto.of(it.field), it.direction) },
            groupByField = table.grouping.field?.let { GithubBoardFieldResDto.of(it) },
            groupSupported = table.grouping.supported,
            groups = table.grouping.groups.map { group ->
                GithubBoardGroupResDto(
                    id = group.id,
                    name = group.name,
                    color = group.color,
                    itemCount = group.items.size,
                    items = group.items.map { GithubBoardItemResDto.of(it, board) },
                )
            },
            appliedFilter = result.appliedFilter,
            unsupportedFilterTokens = result.unsupportedTokens,
        )
    }
}
