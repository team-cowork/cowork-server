package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.UpdateGithubIssueLabelsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class UpdateGithubIssueLabelsServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val commandPublisher: GithubIssueWriteCommandPublisher,
) : UpdateGithubIssueLabelsService {

    @Transactional
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        issueNumber: Int,
        request: UpdateGithubIssueLabelsReqDto,
    ) {
        val repo = repoAccessResolver.resolveForModify(userId, projectId, repoId)
        commandPublisher.publishReplaceLabels(
            owner = repo.owner,
            repo = repo.repo,
            repoId = repoId,
            issueNumber = issueNumber,
            labels = request.labels,
            requestedBy = userId,
            occurredAt = Instant.now(),
        )
    }
}
