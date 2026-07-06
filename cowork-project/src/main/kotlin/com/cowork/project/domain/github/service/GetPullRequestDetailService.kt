package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto

interface GetPullRequestDetailService {
    fun execute(userId: Long, projectId: Long, prNumber: Int): GithubPullRequestResDto
}
