package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto

interface UpdateGithubIssueLabelsService {
    fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        issueNumber: Int,
        request: UpdateGithubIssueLabelsReqDto,
    )
}
