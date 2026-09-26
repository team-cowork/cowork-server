package com.cowork.project.domain.github.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

enum class ChatGithubIssueCreateOperationStatus { PENDING, ACCEPTED, REJECTED }

/**
 * cowork-chat이 발행한 GitHub 이슈 생성 커맨드([ChatGithubIssueCreateCommand])의
 * 처리 결과를 기록하는 멱등성 원장(operation ledger).
 *
 * `operationId`를 PK로 사용해 동일 커맨드의 재전달을 감지하고 재처리를 막는다.
 */
@Entity
@Table(name = "tb_chat_github_issue_create_operations")
class ChatGithubIssueCreateOperation(
    @Id
    @Column(name = "operation_id", nullable = false, length = 36)
    val operationId: String,

    @Column(name = "idempotency_key", nullable = false, length = 36)
    val idempotencyKey: String,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "requester_id", nullable = false)
    val requesterId: Long,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ChatGithubIssueCreateOperationStatus = ChatGithubIssueCreateOperationStatus.PENDING,

    @Column(name = "error_code", length = 100)
    var errorCode: String? = null,

    @Column(name = "error_message", length = 500)
    var errorMessage: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun accept() {
        status = ChatGithubIssueCreateOperationStatus.ACCEPTED
        errorCode = null
        errorMessage = null
    }

    fun reject(code: String, message: String) {
        status = ChatGithubIssueCreateOperationStatus.REJECTED
        errorCode = code
        errorMessage = message
    }
}
