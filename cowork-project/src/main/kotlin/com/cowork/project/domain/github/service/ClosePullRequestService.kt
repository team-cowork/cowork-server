package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestActionResDto

interface ClosePullRequestService {
    fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): GithubPullRequestActionResDto
}
