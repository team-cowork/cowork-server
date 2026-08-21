package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto

interface ListPullRequestFilesService {
    fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): List<GithubPullRequestFileResDto>
}
