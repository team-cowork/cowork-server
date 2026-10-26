package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubActionCommandPublisher
import com.cowork.project.domain.github.event.GithubIssueCreateCommand
import com.cowork.project.domain.github.presentation.data.request.CreateGithubIssueReqDto
import com.cowork.project.domain.github.service.CreateGithubIssueService
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import org.springframework.stereotype.Service

@Service
class CreateGithubIssueServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val commandPublisher: GithubActionCommandPublisher,
) : CreateGithubIssueService {

    // 브로커 적재 확인(최대 5초)을 기다리는 동안 DB 커넥션을 잡지 않도록 서비스 트랜잭션을 두지 않는다.
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
