package com.cowork.project.domain.github.service

interface DeleteGithubCommentService {
    fun execute(userId: Long, projectId: Long, repoId: Long, commentId: Long)
}
