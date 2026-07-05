package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto

interface ListPullRequestFilesService {
    fun listPullRequestFiles(userId: Long, projectId: Long, prNumber: Int): List<GithubPullRequestFileResDto>
}
