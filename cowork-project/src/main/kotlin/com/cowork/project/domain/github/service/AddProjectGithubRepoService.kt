package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.request.AddProjectGithubRepoReqDto
import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto

interface AddProjectGithubRepoService {
    fun execute(userId: Long, projectId: Long, request: AddProjectGithubRepoReqDto): ProjectGithubRepoResDto
}
