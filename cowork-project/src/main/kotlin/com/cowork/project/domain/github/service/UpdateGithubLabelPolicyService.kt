package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubLabelPolicyReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelPolicyResDto

interface UpdateGithubLabelPolicyService {
    fun execute(userId: Long, projectId: Long, repoId: Long, request: UpdateGithubLabelPolicyReqDto): GithubLabelPolicyResDto
}
