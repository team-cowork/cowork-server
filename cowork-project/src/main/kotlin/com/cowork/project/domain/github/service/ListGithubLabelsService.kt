package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto

interface ListGithubLabelsService {
    fun execute(userId: Long, projectId: Long, repoId: Long): List<GithubLabelResDto>
}
