package com.cowork.project.domain.githubBoard.service.impl

import com.cowork.project.domain.githubBoard.presentation.data.response.GithubBoardViewResDto
import com.cowork.project.domain.githubBoard.service.GithubBoardAccess
import com.cowork.project.domain.githubBoard.service.GithubBoardAccessResolver
import com.cowork.project.domain.githubBoard.service.GithubBoardReader
import com.cowork.project.domain.githubBoard.service.QueryGithubBoardViewsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryGithubBoardViewsServiceImpl(
    private val accessResolver: GithubBoardAccessResolver,
    private val boardReader: GithubBoardReader,
) : QueryGithubBoardViewsService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, boardId: Long): List<GithubBoardViewResDto> {
        val link = accessResolver.resolve(userId, projectId, boardId, GithubBoardAccess.READ)
        return boardReader.read(link).views.sortedBy { it.number }.map { GithubBoardViewResDto.of(it) }
    }
}
