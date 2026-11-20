package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.model.GithubBoardItemQuery
import com.cowork.project.domain.githubBoard.model.GithubBoardRoadmapBuilder
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardFieldResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardItemResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardRoadmapGroupResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardRoadmapItemResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardRoadmapResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardSpanResDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardViewResDto
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.GithubBoardItemFilter
import com.cowork.project.domain.githubBoard.service.GithubBoardReader
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardRoadmapService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryGithubBoardRoadmapServiceImpl(
    private val githubBoardAccessResolver: GithubBoardAccessResolver,
    private val githubBoardReader: GithubBoardReader,
    private val githubBoardItemFilter: GithubBoardItemFilter,
) : QueryGithubBoardRoadmapService {

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        viewNumber: Int,
        query: GithubBoardItemQuery,
        startFieldId: String?,
        endFieldId: String?,
    ): GithubBoardRoadmapResDto {
        val link = githubBoardAccessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.READ)
        val board = githubBoardReader.read(link)
        val view = GithubBoardRoadmapBuilder.requireRoadmapView(board, viewNumber)
        val fields = GithubBoardRoadmapBuilder.selectFields(board, startFieldId, endFieldId)
        val result = githubBoardItemFilter.apply(board, view, query, userId)
        val roadmap = GithubBoardRoadmapBuilder.build(board, view, result.items, fields)
        return GithubBoardRoadmapResDto(
            view = GithubBoardViewResDto.of(view),
            startField = GithubBoardFieldResDto.of(roadmap.fields.start),
            endField = GithubBoardFieldResDto.of(roadmap.fields.end),
            dateUnit = roadmap.dateUnit,
            settingsSource = roadmap.fields.source,
            groupByField = roadmap.groupField?.let { GithubBoardFieldResDto.of(it) },
            groupSupported = roadmap.groupSupported,
            groups = roadmap.groups.map { group ->
                GithubBoardRoadmapGroupResDto(
                    id = group.id,
                    name = group.name,
                    color = group.color,
                    itemCount = group.items.size,
                    items = group.items.map {
                        GithubBoardRoadmapItemResDto(
                            item = GithubBoardItemResDto.of(it.item, board),
                            span = GithubBoardSpanResDto(it.span.start, it.span.end),
                        )
                    },
                )
            },
            unscheduledItems = roadmap.unscheduled.map { GithubBoardItemResDto.of(it, board) },
            appliedFilter = result.appliedFilter,
            unsupportedFilterTokens = result.unsupportedTokens,
        )
    }
}
