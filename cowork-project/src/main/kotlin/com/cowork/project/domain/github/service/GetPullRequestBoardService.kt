package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestBoardResDto

interface GetPullRequestBoardService {
    fun getPullRequestBoard(userId: Long, projectId: Long): GithubPullRequestBoardResDto
}
