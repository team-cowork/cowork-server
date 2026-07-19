package com.cowork.project.domain.project.service

import com.cowork.project.domain.github.presentation.data.request.LinkGithubRepoReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto

interface LinkGithubRepoService {
    fun execute(userId: Long, projectId: Long, request: LinkGithubRepoReqDto): ProjectDetailResDto
}
