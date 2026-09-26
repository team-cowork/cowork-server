package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.event.GithubCommentNotificationPublisher
import com.cowork.project.domain.github.event.GithubIssueWriteCommandPublisher
import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.service.CreateGithubCommentService
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubUsernameResolver
import com.cowork.project.domain.user.service.UserProfileProjectionReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Service
class CreateGithubCommentServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val usernameResolver: GithubUsernameResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
    private val commandPublisher: GithubIssueWriteCommandPublisher,
    private val profileReader: UserProfileProjectionReader,
    private val notificationPublisher: GithubCommentNotificationPublisher,
    transactionManager: PlatformTransactionManager,
) : CreateGithubCommentService {
    private val logger = LoggerFactory.getLogger(CreateGithubCommentServiceImpl::class.java)
    private val transaction = TransactionTemplate(transactionManager)

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
            requestedBy = userId,
            occurredAt = Instant.now(),
        )

        // 댓글 생성이 Kafka 비동기 커맨드로 전환되어 이 시점에는 생성된 댓글(author 등)을 동기로
        // 알 수 없다. notifyParentAuthor는 그 정보가 있어야만 부모 작성자를 특정할 수 있으므로
        // 여기서는 더 이상 호출할 수 없다 — 필요하다면 GithubIssueWriteResultHandler의
        // CREATE_COMMENT 성공 처리 경로에서 별도로 재구현해야 한다(이 작업 범위 밖, 알려진 제약사항).
    }

    private fun notifyParentAuthor(
        repo: GithubRepoRef,
        parentType: GithubCommentParentType,
        number: Int,
        comment: GithubCommentResDto,
    ) {
        runCatching {
            val parentAuthorGithubUsername = when (parentType) {
                GithubCommentParentType.ISSUE ->
                    callExecutor.execute { githubAppClient.getIssue(repo.owner, repo.repo, number) }.author
                GithubCommentParentType.PULL_REQUEST ->
                    callExecutor.execute { githubAppClient.getPullRequest(repo.owner, repo.repo, number) }.author
            }
            if (parentAuthorGithubUsername == comment.author) return

            val targetUserId = profileReader.resolveUniqueUserId(parentAuthorGithubUsername)
            transaction.executeWithoutResult {
                notificationPublisher.publishCommentCreated(
                    targetUserId = targetUserId,
                    data = mapOf(
                        "repo" to "${repo.owner}/${repo.repo}",
                        "number" to number,
                        "parentType" to parentType.name,
                        "commentAuthor" to comment.author,
                        "body" to comment.body,
                        "htmlUrl" to comment.htmlUrl,
                    ),
                )
            }
        }.onFailure { ex ->
            logger.warn("댓글 생성 알림 처리 실패 (댓글 생성 자체는 성공) [repo={}/{}, number={}]", repo.owner, repo.repo, number, ex)
        }
    }
}
