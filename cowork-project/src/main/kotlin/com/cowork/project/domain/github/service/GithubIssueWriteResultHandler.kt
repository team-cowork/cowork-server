package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubIssueWriteOperationStatus
import com.cowork.project.domain.github.event.GithubIssueWriteCommandType
import com.cowork.project.domain.github.event.GithubIssueWriteCommentResultPayload
import com.cowork.project.domain.github.event.GithubIssueWriteResult
import com.cowork.project.domain.github.repository.GithubIssueWriteOperationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** [GithubIssueWriteResultHandler.apply]가 반환하는, 트랜잭션 밖에서 처리해야 할 부모 작성자 알림 정보. */
data class PendingCommentCreatedNotification(
    val owner: String,
    val repo: String,
    val parentType: GithubCommentParentType,
    val issueNumber: Int,
    val commentAuthor: String,
    val commentBody: String,
    val commentHtmlUrl: String,
)

@Component
class GithubIssueWriteResultHandler(
    private val operationRepository: GithubIssueWriteOperationRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(GithubIssueWriteResultHandler::class.java)

    /**
     * 원장 상태만 반영하는 짧은 트랜잭션. CREATE_COMMENT가 이 호출로 처음 SUCCEEDED로 전이되면,
     * 부모 작성자 알림에 필요한 정보를 반환한다 — 실제 알림 발행(GitHub 조회 + outbox 기록)은
     * 호출자가 이 트랜잭션이 끝난 뒤 별도로 수행해야 한다(외부 HTTP 호출을 DB 트랜잭션 안에 묶지 않기 위함).
     */
    @Transactional
    fun apply(result: GithubIssueWriteResult): PendingCommentCreatedNotification? {
        val operation = requireNotNull(operationRepository.findByIdForUpdate(result.operationId)) {
            "알 수 없는 GitHub 이슈 쓰기 작업입니다: ${result.operationId}"
        }
        require(operation.idempotencyKey == result.idempotencyKey) { "idempotencyKey가 작업과 일치하지 않습니다." }

        val applied = when (result.status) {
            "SUCCEEDED" -> operation.succeed(result.result?.let { objectMapper.writeValueAsString(it) })
            "FAILED" -> {
                val error = requireNotNull(result.error) { "FAILED에는 error가 필요합니다." }
                operation.fail(error.code, error.message)
            }
            else -> error("지원하지 않는 GitHub 이슈 쓰기 result 상태입니다: ${result.status}")
        }
        operationRepository.save(operation)

        if (!applied) {
            logger.info(
                "Ignore redelivered result for an already-terminal GitHub issue write operation [operationId={}, status={}]",
                result.operationId,
                result.status,
            )
            return null
        }

        if (operation.commandType != GithubIssueWriteCommandType.CREATE_COMMENT ||
            operation.status != GithubIssueWriteOperationStatus.SUCCEEDED
        ) {
            return null
        }

        val parentType = operation.parentType ?: run {
            logger.warn(
                "CREATE_COMMENT 작업에 parentType이 없어 부모 작성자 알림을 생략합니다 [operationId={}]",
                result.operationId,
            )
            return null
        }
        val issueNumber = operation.issueNumber ?: return null
        val comment = result.result?.let {
            objectMapper.convertValue(it, GithubIssueWriteCommentResultPayload::class.java)
        } ?: return null

        return PendingCommentCreatedNotification(
            owner = operation.owner,
            repo = operation.repo,
            parentType = parentType,
            issueNumber = issueNumber,
            commentAuthor = comment.author,
            commentBody = comment.body,
            commentHtmlUrl = comment.htmlUrl,
        )
    }
}
