package com.cowork.project.global.consumer

import com.cowork.project.domain.github.event.GITHUB_ISSUE_WRITE_RESULT_TOPIC
import com.cowork.project.domain.github.event.GithubIssueWriteCommandType
import com.cowork.project.domain.github.event.GithubIssueWriteResult
import com.cowork.project.domain.github.service.GithubCommentParentAuthorNotifier
import com.cowork.project.domain.github.service.GithubIssueWriteResultHandler
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class GithubIssueWriteResultConsumer(
    private val handler: GithubIssueWriteResultHandler,
    private val commentNotifier: GithubCommentParentAuthorNotifier,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [GITHUB_ISSUE_WRITE_RESULT_TOPIC],
        groupId = "cowork-project.github-issue-write-result",
        containerFactory = "githubIssueWriteResultListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val payload = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), GithubIssueWriteResult::class.java))
        }.getOrElse { throw IllegalArgumentException("GitHub 이슈 쓰기 result JSON이 유효하지 않습니다.", it) }
        validate(payload)

        // 원장 반영(짧은 트랜잭션)이 끝난 뒤에만, 트랜잭션 밖에서 부모 작성자 알림(GitHub 조회 + outbox 기록)을 수행한다.
        val pendingNotification = handler.apply(payload)
        if (pendingNotification != null) {
            commentNotifier.notify(
                owner = pendingNotification.owner,
                repo = pendingNotification.repo,
                parentType = pendingNotification.parentType,
                number = pendingNotification.issueNumber,
                commentAuthor = pendingNotification.commentAuthor,
                commentBody = pendingNotification.commentBody,
                commentHtmlUrl = pendingNotification.commentHtmlUrl,
            )
        }
    }

    internal fun validate(result: GithubIssueWriteResult) {
        require(result.schemaVersion == 1) { "지원하지 않는 schemaVersion입니다." }
        require(runCatching { UUID.fromString(result.operationId) }.isSuccess) { "operationId가 UUID가 아닙니다." }
        require(result.idempotencyKey.isNotBlank() && result.idempotencyKey.length <= 200) {
            "idempotencyKey가 유효하지 않습니다."
        }
        require(runCatching { GithubIssueWriteCommandType.valueOf(result.commandType) }.isSuccess) {
            "지원하지 않는 commandType입니다: ${result.commandType}"
        }
        when (result.status) {
            "SUCCEEDED" -> require(result.error == null) { "SUCCEEDED에는 error를 포함할 수 없습니다." }
            "FAILED" -> {
                val error = requireNotNull(result.error) { "FAILED에는 error가 필요합니다." }
                require(error.code.isNotBlank() && error.code.length <= 100) { "error.code가 유효하지 않습니다." }
                require(error.message.isNotBlank() && error.message.length <= 500) {
                    "error.message가 유효하지 않습니다."
                }
            }
            else -> throw IllegalArgumentException("지원하지 않는 result status입니다.")
        }
    }
}
