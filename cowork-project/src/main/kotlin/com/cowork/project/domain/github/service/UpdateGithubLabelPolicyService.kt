package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubLabelPolicyReqDto
import com.cowork.project.domain.githubPreference.presentation.data.response.GithubLabelPolicyOperationAcceptedResDto

interface UpdateGithubLabelPolicyService {
    fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        idempotencyKey: String,
        request: UpdateGithubLabelPolicyReqDto,
    ): GithubLabelPolicyOperationAcceptedResDto
}
