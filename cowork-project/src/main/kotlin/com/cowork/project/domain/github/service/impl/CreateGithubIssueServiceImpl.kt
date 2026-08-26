package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubActionCommandPublisher
import com.cowork.project.domain.github.event.GithubIssueCreateCommand
import com.cowork.project.domain.github.presentation.data.request.CreateGithubIssueReqDto
import com.cowork.project.domain.github.service.CreateGithubIssueService
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CreateGithubIssueServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val commandPublisher: GithubActionCommandPublisher,
) : CreateGithubIssueService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long, request: CreateGithubIssueReqDto) {
        val repo = repoAccessResolver.resolveForModify(userId, projectId, repoId)
        commandPublisher.publishIssueCreate(
            GithubIssueCreateCommand(
                owner = repo.owner,
                repo = repo.repo,
                title = request.title,
                body = request.body,
                labels = listOfNotNull(request.label),
            ),
        )
    }
}
