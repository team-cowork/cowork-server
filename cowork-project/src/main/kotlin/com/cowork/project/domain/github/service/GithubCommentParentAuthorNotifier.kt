package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.event.GithubCommentNotificationPublisher
import com.cowork.project.domain.user.service.UserProfileProjectionReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 댓글 생성 성공 후 부모 이슈/PR 작성자에게 알림을 보낸다. 댓글 생성이 `github.issue-write.command.v1`
 * 비동기 커맨드로 전환되면서, 실제 댓글이 만들어졌는지는 [com.cowork.project.domain.github.service.GithubIssueWriteResultHandler]가
 * `github.issue-write.result.v1`를 받은 시점에야 알 수 있다 — 그래서 이 호출은 그 result 처리 이후,
 * DB 트랜잭션 밖에서 이뤄진다(부모 조회를 트랜잭션 안에 묶어두지 않기 위함). 알림 발행(outbox
 * 기록)만 짧은 별도 트랜잭션으로 감싼다.
 */
@Component
class GithubCommentParentAuthorNotifier(
    private val issueReader: GithubIssueReader,
    private val pullRequestReader: GithubPullRequestReader,
    private val profileReader: UserProfileProjectionReader,
    private val notificationPublisher: GithubCommentNotificationPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(GithubCommentParentAuthorNotifier::class.java)
    private val transaction = TransactionTemplate(transactionManager)

    fun notify(
        owner: String,
        repo: String,
        parentType: GithubCommentParentType,
        number: Int,
        commentAuthor: String,
        commentBody: String,
        commentHtmlUrl: String,
    ) {
        runCatching {
            val parentAuthorGithubUsername = when (parentType) {
                GithubCommentParentType.ISSUE -> issueReader.findAuthor(owner, repo, number)
                GithubCommentParentType.PULL_REQUEST -> pullRequestReader.findAuthor(owner, repo, number)
            }
            if (parentAuthorGithubUsername == commentAuthor) return

            val targetUserId = profileReader.resolveUniqueUserId(parentAuthorGithubUsername)
            transaction.executeWithoutResult {
                notificationPublisher.publishCommentCreated(
                    targetUserId = targetUserId,
                    data = mapOf(
                        "repo" to "$owner/$repo",
                        "number" to number,
                        "parentType" to parentType.name,
                        "commentAuthor" to commentAuthor,
                        "body" to commentBody,
                        "htmlUrl" to commentHtmlUrl,
                    ),
                )
            }
        }.onFailure { ex ->
            logger.warn("댓글 생성 알림 처리 실패 (댓글 생성 자체는 성공) [repo={}/{}, number={}]", owner, repo, number, ex)
        }
    }
}
