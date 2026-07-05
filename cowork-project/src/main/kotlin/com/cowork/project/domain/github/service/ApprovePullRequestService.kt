package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubApproveResultResDto

interface ApprovePullRequestService {
    fun approvePullRequest(userId: Long, projectId: Long, prNumber: Int): GithubApproveResultResDto
}
