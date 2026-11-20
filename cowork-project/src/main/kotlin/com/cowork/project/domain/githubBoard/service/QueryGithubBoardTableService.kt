package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.model.GithubBoardItemQuery
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardTableResDto

interface QueryGithubBoardTableService {
    fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        viewNumber: Int,
        query: GithubBoardItemQuery,
    ): GithubBoardTableResDto
}
