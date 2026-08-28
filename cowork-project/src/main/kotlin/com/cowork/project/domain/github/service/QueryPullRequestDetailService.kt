package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto

interface QueryPullRequestDetailService {
    fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): GithubPullRequestResDto
}
