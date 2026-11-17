package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.presentation.data.request.MoveGithubBoardItemReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationAcceptedResDto

interface MoveGithubBoardItemService {
    fun execute(
        userId: Long,
        projectId: Long,
        boardId: Long,
        itemId: String,
        request: MoveGithubBoardItemReqDto,
    ): GithubBoardOperationAcceptedResDto
}
