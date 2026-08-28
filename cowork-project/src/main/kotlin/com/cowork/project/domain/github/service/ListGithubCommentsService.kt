package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto

interface ListGithubCommentsService {
    fun execute(userId: Long, projectId: Long, repoId: Long, number: Int): List<GithubCommentResDto>
}
