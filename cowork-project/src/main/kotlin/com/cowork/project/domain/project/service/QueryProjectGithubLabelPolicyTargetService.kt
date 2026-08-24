package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.response.ProjectGithubLabelPolicyTargetResDto

interface QueryProjectGithubLabelPolicyTargetService {
    fun execute(owner: String, repo: String): List<ProjectGithubLabelPolicyTargetResDto>
}
