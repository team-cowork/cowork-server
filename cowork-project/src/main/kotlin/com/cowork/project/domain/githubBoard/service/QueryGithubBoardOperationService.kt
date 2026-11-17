package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardOperationResDto

interface QueryGithubBoardOperationService {
    fun execute(userId: Long, projectId: Long, boardId: Long, operationId: String): GithubBoardOperationResDto
}
