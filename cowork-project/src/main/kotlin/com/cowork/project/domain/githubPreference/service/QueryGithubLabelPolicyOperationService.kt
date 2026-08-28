package com.cowork.project.domain.githubPreference.service

import com.cowork.project.domain.githubPreference.presentation.data.response.GithubLabelPolicyOperationResDto

interface QueryGithubLabelPolicyOperationService {
    fun execute(userId: Long, projectId: Long, repoId: Long, operationId: String): GithubLabelPolicyOperationResDto
}
