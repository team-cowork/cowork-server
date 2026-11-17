package com.cowork.project.domain.githubBoard.service

interface RemoveProjectGithubBoardService {
    fun execute(userId: Long, projectId: Long, boardId: Long)
}
