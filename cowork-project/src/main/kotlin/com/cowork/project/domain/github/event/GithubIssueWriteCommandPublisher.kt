package com.cowork.project.domain.github.event

import com.cowork.project.domain.github.entity.GithubIssueWriteOperation
import com.cowork.project.domain.github.repository.GithubIssueWriteOperationRepository
import com.cowork.project.global.outbox.OutboxWriter
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

/**
 * 이슈 라벨 전체 교체·댓글 생성/수정/삭제를 `github-app.issue-write.command`로 발행한다.
 * REST(Feign) 동기 호출을 대체하며, 처리 결과는 [GithubIssueWriteResultConsumer][com.cowork.project.global.consumer.GithubIssueWriteResultConsumer]가
 * `github-app.issue-write.result`로 수신해 [GithubIssueWriteOperation] 원장에 반영한다.
 *
 * ## idempotencyKey 설계
 *
 * cowork-github-app은 `idempotencyKey`로 중복 제거를 하지 않으므로(그대로 echo만 함), 이미 발행이
 * 끝난 작업에 대해 재발행하지 않는 책임은 이 publisher가 진다. 각 command 타입별로 "무엇을 같은
 * 작업으로 볼지"가 다르므로 key 구성도 다르게 가져간다.
 *
 * - `REPLACE_LABELS`/`CREATE_COMMENT`/`UPDATE_COMMENT`: 라벨 전체 교체·댓글 생성·댓글 수정은
 *   같은 이슈/댓글에 대해서도 서로 다른 내용으로 반복해서 발생하는 정상적인 별개의 업무 행위다
 *   (사용자가 라벨을 다시 바꾸거나, 같은 이슈에 댓글을 여러 번 달거나, 댓글을 여러 번 고쳐 쓸 수 있다).
 *   이 세 endpoint는 클라이언트가 제공하는 Idempotency-Key도 없으므로, 호출 하나하나를 별개의
 *   작업으로 보고 매 호출마다 새 nonce를 key에 포함시킨다 — 그래야 과거에 종결된 작업과 절대
 *   충돌하지 않고 항상 새로 발행된다.
 * - `DELETE_COMMENT`: 삭제는 반대로 "같은 댓글을 두 번 삭제"가 곧 "같은 의도의 재시도"다(중복
 *   더블클릭, 네트워크 재시도 등). repoId+commentId만으로 결정적인 key를 만들어, 같은 댓글에 대한
 *   두 번째 삭제 요청이 이미 만들어진 operation을 재사용하고 재발행하지 않도록 한다. 이는 이 계약의
 *   확인된 결함 — 재전달된 DELETE_COMMENT가 이미 삭제됐음에도 404로 FAILED가 돌아올 수 있음 —
 *   이 실제로 중복 삭제 Kafka 커맨드를 만들어내지 않도록 막아주는 유일한 방어선이다.
 */
@Component
class GithubIssueWriteCommandPublisher(
    private val operationRepository: GithubIssueWriteOperationRepository,
    private val outboxWriter: OutboxWriter,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishReplaceLabels(
        owner: String,
        repo: String,
        repoId: Long,
        issueNumber: Int,
        labels: List<String>,
        requestedBy: Long,
        occurredAt: Instant,
    ): String {
        val idempotencyKey = "github-issue-write:REPLACE_LABELS:$repoId:$issueNumber:$requestedBy:${UUID.randomUUID()}"
        return publish(
            commandType = GithubIssueWriteCommandType.REPLACE_LABELS,
            idempotencyKey = idempotencyKey,
            owner = owner,
            repo = repo,
            repoId = repoId,
            issueNumber = issueNumber,
            commentId = null,
            requestedBy = requestedBy,
            occurredAt = occurredAt,
            payload = GithubReplaceLabelsPayload(issueNumber, labels),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishCreateComment(
        owner: String,
        repo: String,
        repoId: Long,
        issueNumber: Int,
        body: String,
        requesterGithubUsername: String,
        requestedBy: Long,
        occurredAt: Instant,
    ): String {
        val idempotencyKey = "github-issue-write:CREATE_COMMENT:$repoId:$issueNumber:$requestedBy:${UUID.randomUUID()}"
        return publish(
            commandType = GithubIssueWriteCommandType.CREATE_COMMENT,
            idempotencyKey = idempotencyKey,
            owner = owner,
            repo = repo,
            repoId = repoId,
            issueNumber = issueNumber,
            commentId = null,
            requestedBy = requestedBy,
            occurredAt = occurredAt,
            payload = GithubCreateCommentPayload(issueNumber, body, requesterGithubUsername),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishUpdateComment(
        owner: String,
        repo: String,
        repoId: Long,
        commentId: Long,
        body: String,
        requestedBy: Long,
        occurredAt: Instant,
    ): String {
        val idempotencyKey = "github-issue-write:UPDATE_COMMENT:$repoId:$commentId:$requestedBy:${UUID.randomUUID()}"
        return publish(
            commandType = GithubIssueWriteCommandType.UPDATE_COMMENT,
            idempotencyKey = idempotencyKey,
            owner = owner,
            repo = repo,
            repoId = repoId,
            issueNumber = null,
            commentId = commentId,
            requestedBy = requestedBy,
            occurredAt = occurredAt,
            payload = GithubUpdateCommentPayload(commentId, body),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishDeleteComment(
        owner: String,
        repo: String,
        repoId: Long,
        commentId: Long,
        requestedBy: Long,
        occurredAt: Instant,
    ): String {
        val idempotencyKey = "github-issue-write:DELETE_COMMENT:$repoId:$commentId"
        return publish(
            commandType = GithubIssueWriteCommandType.DELETE_COMMENT,
            idempotencyKey = idempotencyKey,
            owner = owner,
            repo = repo,
            repoId = repoId,
            issueNumber = null,
            commentId = commentId,
            requestedBy = requestedBy,
            occurredAt = occurredAt,
            payload = GithubDeleteCommentPayload(commentId),
        )
    }

    private fun publish(
        commandType: GithubIssueWriteCommandType,
        idempotencyKey: String,
        owner: String,
        repo: String,
        repoId: Long,
        issueNumber: Int?,
        commentId: Long?,
        requestedBy: Long,
        occurredAt: Instant,
        payload: Any,
    ): String {
        val operationId = UUID.nameUUIDFromBytes(idempotencyKey.toByteArray(UTF_8)).toString()
        if (operationRepository.existsById(operationId)) return operationId

        try {
            operationRepository.save(
                GithubIssueWriteOperation(
                    operationId = operationId,
                    idempotencyKey = idempotencyKey,
                    commandType = commandType,
                    repoId = repoId,
                    issueNumber = issueNumber,
                    commentId = commentId,
                    requestedBy = requestedBy,
                ),
            )
        } catch (ex: DataIntegrityViolationException) {
            // 동시 요청이 같은 operationId를 먼저 선점했다 — 그 요청이 발행을 책임지므로 재발행하지 않는다.
            return operationId
        }

        outboxWriter.enqueue(
            GITHUB_ISSUE_WRITE_COMMAND_TOPIC,
            "$owner/$repo",
            GithubIssueWriteCommand(
                operationId = operationId,
                idempotencyKey = idempotencyKey,
                commandType = commandType,
                owner = owner,
                repo = repo,
                requestedBy = requestedBy,
                occurredAt = occurredAt,
                payload = payload,
            ),
        )
        return operationId
    }
}
