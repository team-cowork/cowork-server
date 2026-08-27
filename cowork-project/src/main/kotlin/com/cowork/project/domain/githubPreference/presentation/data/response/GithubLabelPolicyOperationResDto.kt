package com.cowork.project.domain.githubPreference.presentation.data.response

import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperation
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "GitHub 저장소 라벨 정책 비동기 작업 접수 정보")
data class GithubLabelPolicyOperationAcceptedResDto(
    @field:Schema(description = "작업 ID")
    val operationId: String,
    @field:Schema(description = "작업 상태", example = "PENDING")
    val status: GithubRepoSettingOperationStatus,
) {
    companion object {
        fun of(operation: GithubRepoSettingOperation) = GithubLabelPolicyOperationAcceptedResDto(
            operationId = operation.operationId,
            status = operation.status,
        )
    }
}

@Schema(description = "GitHub 저장소 라벨 정책 비동기 작업 상태")
data class GithubLabelPolicyOperationResDto(
    val operationId: String,
    val status: GithubRepoSettingOperationStatus,
    val autoApply: Boolean?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(operation: GithubRepoSettingOperation) = GithubLabelPolicyOperationResDto(
            operationId = operation.operationId,
            status = operation.status,
            autoApply = operation.resultAutoApply,
            errorCode = operation.errorCode,
            errorMessage = operation.errorMessage,
            createdAt = operation.createdAt,
            updatedAt = operation.updatedAt,
        )
    }
}
