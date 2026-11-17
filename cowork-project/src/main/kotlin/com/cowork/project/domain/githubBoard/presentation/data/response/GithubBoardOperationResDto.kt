package com.cowork.project.domain.githubBoard.presentation.data.response

import com.cowork.project.domain.githubBoard.entity.GithubProjectItemWriteOperation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class GithubBoardOperationAcceptedResDto(
    @field:Schema(description = "카드 이동 작업 ID", example = "0f8fad5b-d9cb-469f-a165-70867728950e")
    val operationId: String,
)

data class GithubBoardOperationResDto(
    @field:Schema(description = "작업 ID", example = "0f8fad5b-d9cb-469f-a165-70867728950e")
    val operationId: String,
    @field:Schema(description = "작업 종류", example = "MOVE_ITEM", allowableValues = ["MOVE_ITEM", "REORDER_ITEM"])
    val commandType: String,
    @field:Schema(description = "대상 GitHub Project item ID", nullable = true)
    val itemId: String?,
    @field:Schema(description = "상태", example = "SUCCEEDED", allowableValues = ["PENDING", "SUCCEEDED", "FAILED"])
    val status: String,
    @field:Schema(description = "실패 코드. GitHub가 거부하면 HTTP 상태 코드", example = "404", nullable = true)
    val errorCode: String?,
    @field:Schema(description = "실패 사유", nullable = true)
    val errorMessage: String?,
    @field:Schema(description = "요청 시각")
    val createdAt: LocalDateTime,
    @field:Schema(description = "마지막 상태 변경 시각")
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(operation: GithubProjectItemWriteOperation) = GithubBoardOperationResDto(
            operationId = operation.operationId,
            commandType = operation.commandType.name,
            itemId = operation.itemId,
            status = operation.status.name,
            errorCode = operation.errorCode,
            errorMessage = operation.errorMessage,
            createdAt = operation.createdAt,
            updatedAt = operation.updatedAt,
        )
    }
}
