package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.github.service.GithubSyncRequestScheduler
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.RequestGithubBoardSyncService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RequestGithubBoardSyncServiceImpl(
    private val githubBoardAccessResolver: GithubBoardAccessResolver,
    private val githubSyncRequestScheduler: GithubSyncRequestScheduler,
) : RequestGithubBoardSyncService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, boardId: Long) {
        val board = githubBoardAccessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.WRITE)
        githubSyncRequestScheduler.requestProjectSync(board.ownerLogin, board.projectNumber, force = true)
    }
}
