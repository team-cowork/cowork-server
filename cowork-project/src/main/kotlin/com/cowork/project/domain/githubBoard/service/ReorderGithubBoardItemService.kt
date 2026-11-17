package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.presentation.data.request.ReorderGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationAcceptedResDto

interface ReorderGithubBoardItemService {
    fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: ReorderGithubBoardItemReqDto,
    ): GithubBoardOperationAcceptedResDto
}
