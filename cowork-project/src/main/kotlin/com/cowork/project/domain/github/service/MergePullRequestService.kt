package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubMergeResultResDto

interface MergePullRequestService {
    fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): GithubMergeResultResDto
}
