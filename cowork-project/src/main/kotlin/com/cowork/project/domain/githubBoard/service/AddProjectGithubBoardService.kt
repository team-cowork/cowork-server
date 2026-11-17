package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.githubBoard.presentation.data.request.AddProjectGithubBoardReqDto
import com.cowork.project.domain.githubBoard.presentation.data.response.ProjectGithubBoardResDto

interface AddProjectGithubBoardService {
    fun execute(userId: Long, projectId: Long, request: AddProjectGithubBoardReqDto): ProjectGithubBoardResDto
}
