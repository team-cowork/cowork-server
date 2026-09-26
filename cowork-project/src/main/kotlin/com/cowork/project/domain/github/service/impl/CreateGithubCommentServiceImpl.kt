package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.service.CreateGithubCommentService
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubUsernameResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 댓글 생성을 `github-app.issue-write.command` Kafka 커맨드로 발행한다(비동기). 실제 생성 성공 여부와
 * 부모 이슈/PR 작성자 알림은 [com.cowork.project.domain.github.service.GithubIssueWriteResultHandler]가
 * `github-app.issue-write.result`를 받은 뒤 [com.cowork.project.domain.github.service.GithubCommentParentAuthorNotifier]로 처리한다.
 */
@Service
class CreateGithubCommentServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val usernameResolver: GithubUsernameResolver,
    private val commandPublisher: GithubIssueWriteCommandPublisher,
) : CreateGithubCommentService {

    @Transactional
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        parentType: GithubCommentParentType,
        number: Int,
        request: CreateGithubCommentReqDto,
    ) {
        val repo = repoAccessResolver.resolveForRead(userId, projectId, repoId)
        val requesterGithubUsername = usernameResolver.resolve(userId)

        commandPublisher.publishCreateComment(
            owner = repo.owner,
            repo = repo.repo,
            repoId = repoId,
            issueNumber = number,
            body = request.body,
            requesterGithubUsername = requesterGithubUsername,
            parentType = parentType,
            requestedBy = userId,
            occurredAt = Instant.now(),
        )
    }
}
