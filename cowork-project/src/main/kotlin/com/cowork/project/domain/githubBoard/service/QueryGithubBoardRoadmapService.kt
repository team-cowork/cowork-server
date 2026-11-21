package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.model.GithubBoardItemQuery
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardRoadmapResDto

interface QueryGithubBoardRoadmapService {
    fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        viewNumber: Int,
        query: GithubBoardItemQuery,
        startFieldId: String?,
        endFieldId: String?,
    ): GithubBoardRoadmapResDto
}
