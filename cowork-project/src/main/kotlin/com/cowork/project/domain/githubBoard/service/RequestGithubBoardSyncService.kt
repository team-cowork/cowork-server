package com.cowork.project.domain.githubBoard.service

interface RequestGithubBoardSyncService {
    fun execute(userId: Long, projectId: Long, boardId: Long)
}
