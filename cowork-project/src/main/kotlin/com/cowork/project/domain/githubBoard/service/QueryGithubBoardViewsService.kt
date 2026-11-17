package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardViewResDto

interface QueryGithubBoardViewsService {
    fun execute(userId: Long, projectId: Long, boardId: Long): List<GithubBoardViewResDto>
}
