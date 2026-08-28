package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubLabelPolicyResDto

interface QueryGithubLabelPolicyService {
    fun execute(userId: Long, projectId: Long, repoId: Long): GithubLabelPolicyResDto
}
