package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.request.CreateGithubIssueReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto

interface CreateGithubIssueService {
    fun execute(userId: Long, projectId: Long, repoId: Long, request: CreateGithubIssueReqDto): GithubIssueResDto
}
