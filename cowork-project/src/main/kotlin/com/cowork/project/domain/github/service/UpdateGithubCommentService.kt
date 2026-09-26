package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.request.UpdateGithubCommentReqDto

interface UpdateGithubCommentService {
    fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        commentId: Long,
        request: UpdateGithubCommentReqDto,
    )
}
