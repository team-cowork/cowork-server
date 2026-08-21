package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto

interface QueryProjectGithubReposService {
    fun execute(userId: Long, projectId: Long): List<GithubRepoSummaryResDto>
}
