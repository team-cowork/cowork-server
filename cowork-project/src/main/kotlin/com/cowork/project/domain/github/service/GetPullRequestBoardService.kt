package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestBoardResDto

interface GetPullRequestBoardService {
    fun execute(userId: Long, projectId: Long): GithubPullRequestBoardResDto
}
