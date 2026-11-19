package com.cowork.project.domain.githubBoard.presentation.data.response

import com.cowork.project.domain.githubBoard.entity.GithubProjectItemWriteOperation
import com.cowork.project.domain.githubBoard.entity.GithubProjectItemWriteOperationStatus
import com.cowork.project.domain.githubBoard.event.GithubProjectItemWriteCommandType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "보드 조회 결과는 GitHub 웹훅을 수신한 뒤 갱신됩니다.")
data class GithubBoardOperationAcceptedResDto(
    @field:Schema(title = "작업 ID", format = "uuid", example = "0f8fad5b-d9cb-469f-a165-70867728950e")
    val operationId: String,
)

data class GithubBoardOperationResDto(
    @field:Schema(title = "작업 ID", format = "uuid", example = "0f8fad5b-d9cb-469f-a165-70867728950e")
    val operationId: String,
    @field:Schema(title = "작업 종류", implementation = GithubProjectItemWriteCommandType::class, example = "CREATE_DRAFT")
    val commandType: String,
    @field:Schema(
        title = "카드 ID",
        description = "생성과 추가 작업에서는 성공 후 반환합니다.",
        example = "PVTI_lADOBk9Ru84AqHDWzgUcFQg",
        nullable = true,
    )
    val itemId: String?,
    @field:Schema(title = "작업 상태", implementation = GithubProjectItemWriteOperationStatus::class, example = "SUCCEEDED")
    val status: String,
    @field:Schema(
        title = "실패 코드",
        description = "GitHub가 요청을 거부하면 HTTP 상태 코드를 반환합니다.",
        example = "404",
        nullable = true,
    )
    val errorCode: String?,
    @field:Schema(title = "실패 사유", nullable = true)
    val errorMessage: String?,
    @field:Schema(title = "요청 시각")
    val createdAt: LocalDateTime,
    @field:Schema(title = "마지막 상태 변경 시각")
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
