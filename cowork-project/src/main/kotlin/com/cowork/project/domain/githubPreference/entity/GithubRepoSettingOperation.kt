package com.cowork.project.domain.githubPreference.entity

import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDateTime

enum class GithubRepoSettingOperationStatus { PENDING, PROCESSING, SUCCEEDED, FAILED }

@Entity
@Table(name = "tb_github_repo_setting_operations")
class GithubRepoSettingOperation(
    @Id
    @Column(name = "operation_id", nullable = false, length = 36)
    val operationId: String,

    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "repo_id", nullable = false)
    val repoId: Long,

    @Column(name = "requested_by", nullable = false)
    val requestedBy: Long,

    @Column(name = "requested_auto_apply", nullable = false)
    val requestedAutoApply: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: GithubRepoSettingOperationStatus = GithubRepoSettingOperationStatus.PENDING,

    @Column(name = "result_auto_apply")
    var resultAutoApply: Boolean? = null,

    @Column(name = "expected_state_occurred_at")
    var expectedStateOccurredAt: Instant? = null,

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
    fun matches(projectId: Long, repoId: Long, requestedBy: Long, requestedAutoApply: Boolean): Boolean =
        this.projectId == projectId &&
            this.repoId == repoId &&
            this.requestedBy == requestedBy &&
            this.requestedAutoApply == requestedAutoApply

    fun markProcessing(autoApply: Boolean, stateOccurredAt: Instant) {
        val expectedVersion = stateOccurredAt.toProjectionPrecision()
        if (status != GithubRepoSettingOperationStatus.PENDING) {
            require(
                status in setOf(
                    GithubRepoSettingOperationStatus.PROCESSING,
                    GithubRepoSettingOperationStatus.SUCCEEDED,
                ) && resultAutoApply == autoApply && expectedStateOccurredAt == expectedVersion,
            ) { "중복 SUCCEEDED result가 기존 작업 결과와 일치하지 않습니다." }
            return
        }
        status = GithubRepoSettingOperationStatus.PROCESSING
        resultAutoApply = autoApply
        expectedStateOccurredAt = expectedVersion
        errorCode = null
        errorMessage = null
    }

    fun completeIfObserved(labelAutoApply: Boolean, deleted: Boolean, stateOccurredAt: Instant): Boolean {
        if (status != GithubRepoSettingOperationStatus.PROCESSING) return false
        val expected = expectedStateOccurredAt ?: return false
        val observed = stateOccurredAt.toProjectionPrecision()
        if (observed.isBefore(expected)) return false
        if (observed == expected && (deleted || labelAutoApply != resultAutoApply)) return false
        status = GithubRepoSettingOperationStatus.SUCCEEDED
        return true
    }

    fun fail(code: String, message: String) {
        if (status != GithubRepoSettingOperationStatus.PENDING) {
            require(status == GithubRepoSettingOperationStatus.FAILED && errorCode == code && errorMessage == message) {
                "중복 FAILED result가 기존 작업 결과와 일치하지 않습니다."
            }
            return
        }
        status = GithubRepoSettingOperationStatus.FAILED
        resultAutoApply = null
        expectedStateOccurredAt = null
        errorCode = code
        errorMessage = message
    }
}
