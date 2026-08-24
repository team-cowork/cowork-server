package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto

interface QueryIssueBoardService {
    fun execute(userId: Long, projectId: Long, repoId: Long): List<GithubIssueResDto>
}
