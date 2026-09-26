package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto

interface CreateGithubCommentService {
    fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        parentType: GithubCommentParentType,
        number: Int,
        request: CreateGithubCommentReqDto,
    )
}
