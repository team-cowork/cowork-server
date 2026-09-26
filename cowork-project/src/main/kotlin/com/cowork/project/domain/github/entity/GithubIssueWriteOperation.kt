package com.cowork.project.domain.github.entity

import com.cowork.project.domain.github.event.GithubIssueWriteCommandType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

enum class GithubIssueWriteOperationStatus { PENDING, SUCCEEDED, FAILED }

/**
 * 이슈 라벨 전체 교체·댓글 생성/수정/삭제 Kafka 커맨드([com.cowork.project.domain.github.event.GithubIssueWriteCommand])의
 * 멱등성 원장(operation ledger). `operationId`를 PK로 사용해 동일 command의 재발행을 막고,
 * 결과 반영 시 `idempotencyKey` 일치 여부를 검증하는 기준이 된다.
 *
 * cowork-github-app은 `idempotencyKey`로 중복 제거를 하지 않으므로(그대로 echo만 함), 이미 종결
 * (SUCCEEDED/FAILED) 상태인 작업에 대해서는 재발행하지 않는 책임이 이 원장을 사용하는
 * publisher 쪽에 있다. `DELETE_COMMENT`는 재전달 시 이미 삭제됐음에도 404로 인한 FAILED가
 * 돌아올 수 있으므로, 결과 반영도 이미 종결된 작업에 대해서는 상태 불일치 여부와 무관하게
 * 안전하게 무시(no-op)한다.
 */
@Entity
@Table(name = "tb_github_issue_write_operations")
class GithubIssueWriteOperation(
    @Id
    @Column(name = "operation_id", nullable = false, length = 36)
    val operationId: String,

    @Column(name = "idempotency_key", nullable = false, length = 200)
    val idempotencyKey: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, length = 20)
    val commandType: GithubIssueWriteCommandType,

    @Column(name = "repo_id", nullable = false)
    val repoId: Long,

    @Column(name = "issue_number")
    val issueNumber: Int? = null,

    @Column(name = "comment_id")
    val commentId: Long? = null,

    @Column(name = "requested_by", nullable = false)
    val requestedBy: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: GithubIssueWriteOperationStatus = GithubIssueWriteOperationStatus.PENDING,

    @Column(name = "result_snapshot", columnDefinition = "TEXT")
    var resultSnapshot: String? = null,

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
    /** SUCCEEDED result 반영. 이미 종결된 작업이면 재전달로 간주해 안전하게 무시한다. */
    fun succeed(resultSnapshotJson: String?): Boolean {
        if (status != GithubIssueWriteOperationStatus.PENDING) return false
        status = GithubIssueWriteOperationStatus.SUCCEEDED
        resultSnapshot = resultSnapshotJson
        errorCode = null
        errorMessage = null
        return true
    }

    /** FAILED result 반영. 이미 종결된 작업이면 재전달로 간주해 안전하게 무시한다. */
    fun fail(code: String, message: String): Boolean {
        if (status != GithubIssueWriteOperationStatus.PENDING) return false
        status = GithubIssueWriteOperationStatus.FAILED
        errorCode = code
        errorMessage = message
        return true
    }
}
