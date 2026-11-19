package com.cowork.project.domain.githubBoard.entity

import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

enum class GithubProjectItemWriteOperationStatus { PENDING, SUCCEEDED, FAILED }

/**
 * 카드 쓰기 커맨드의 작업 원장. 결과가 도착하면 `SUCCEEDED`나 `FAILED`로 바뀌고, 이미 끝난 작업에 다시 온 결과는 무시한다.
 * Projection은 이 결과가 아니라 `projects_v2_item` 웹훅으로 바뀐다.
 */
@Entity
@Table(name = "tb_github_project_item_write_operations")
class GithubProjectItemWriteOperation(
    @Id
    @Column(name = "operation_id", nullable = false, length = 36)
    val operationId: String,

    @Column(name = "idempotency_key", nullable = false, length = 200)
    val idempotencyKey: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, length = 20)
    val commandType: GithubProjectItemWriteCommandType,

    @Column(name = "board_id", nullable = false)
    val boardId: Long,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "item_id", length = 100)
    var itemId: String?,

    @Column(name = "requested_by", nullable = false)
    val requestedBy: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: GithubProjectItemWriteOperationStatus = GithubProjectItemWriteOperationStatus.PENDING,

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
    fun succeed(resultItemId: String? = null): Boolean {
        if (status != GithubProjectItemWriteOperationStatus.PENDING) return false
        if (itemId == null) itemId = resultItemId
        status = GithubProjectItemWriteOperationStatus.SUCCEEDED
        return true
    }

    fun fail(code: String, message: String): Boolean {
        if (status != GithubProjectItemWriteOperationStatus.PENDING) return false
        status = GithubProjectItemWriteOperationStatus.FAILED
        errorCode = code
        errorMessage = message
        return true
    }
}
