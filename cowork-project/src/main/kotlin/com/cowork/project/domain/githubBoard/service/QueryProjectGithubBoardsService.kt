package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.presentation.data.response.ProjectGithubBoardResDto

interface QueryProjectGithubBoardsService {
    fun execute(userId: Long, projectId: Long): List<ProjectGithubBoardResDto>
}
