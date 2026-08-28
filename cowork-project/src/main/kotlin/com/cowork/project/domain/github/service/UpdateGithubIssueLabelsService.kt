package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto

interface UpdateGithubIssueLabelsService {
    fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        issueNumber: Int,
        request: UpdateGithubIssueLabelsReqDto,
    ): GithubIssueResDto
}
