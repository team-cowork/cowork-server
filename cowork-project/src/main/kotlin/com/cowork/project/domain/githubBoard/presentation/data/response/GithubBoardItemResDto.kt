package com.cowork.project.domain.githubBoard.presentation.data.response

import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.model.GithubBoard
import io.swagger.v3.oas.annotations.media.Schema

data class GithubBoardLabelResDto(
    @field:Schema(title = "라벨 이름", example = "bug")
    val name: String,
    @field:Schema(title = "라벨 색상", pattern = "^[0-9a-fA-F]{6}$", example = "d73a4a")
    val color: String,
)

data class GithubBoardFieldValueResDto(
    @field:Schema(title = "필드 ID", example = "PVTSSF_lADOBk9Ru84AqHDWzgg")
    val fieldId: String,
    @field:Schema(title = "필드 이름", example = "Status")
    val fieldName: String,
    @field:Schema(
        title = "필드 타입",
        example = "SINGLE_SELECT",
        allowableValues = ["TEXT", "NUMBER", "DATE", "SINGLE_SELECT", "ITERATION"],
    )
    val dataType: String,
    @field:Schema(
        title = "표시 값",
        example = "In Progress",
        nullable = true,
    )
    val displayValue: String?,
    @field:Schema(title = "선택 항목 ID", nullable = true)
    val optionId: String?,
    @field:Schema(title = "반복 주기 ID", nullable = true)
    val iterationId: String?,
)

data class GithubBoardItemResDto(
    @field:Schema(title = "카드 ID", example = "PVTI_lADOBk9Ru84AqHDWzgUcFQg")
    val id: String,
    @field:Schema(
        title = "카드 종류",
        example = "ISSUE",
        allowableValues = ["ISSUE", "PULL_REQUEST", "DRAFT_ISSUE", "REDACTED"],
    )
    val type: String,
    @field:Schema(title = "카드 제목", description = "접근할 수 없는 카드의 제목은 빈 문자열로 반환합니다.", example = "로그인 오류 수정")
    val title: String,
    @field:Schema(title = "이슈 또는 PR 번호", example = "42", nullable = true)
    val number: Int?,
    @field:Schema(
        title = "이슈 또는 PR 상태",
        example = "OPEN",
        nullable = true,
        allowableValues = ["OPEN", "CLOSED", "MERGED"],
    )
    val state: String?,
    @field:Schema(title = "GitHub URL", format = "uri", nullable = true)
    val url: String?,
    @field:Schema(title = "저장소", example = "my-org/app", nullable = true)
    val repository: String?,
    @field:Schema(title = "담당자 GitHub 사용자명")
    val assignees: List<String>,
    @field:Schema(title = "라벨")
    val labels: List<GithubBoardLabelResDto>,
    @field:Schema(title = "마일스톤 제목", nullable = true)
    val milestone: String?,
    @field:Schema(title = "Draft 여부", example = "false")
    val draft: Boolean,
    @field:Schema(title = "필드 값")
    val fields: List<GithubBoardFieldValueResDto>,
    @field:Schema(title = "Draft 본문", nullable = true)
    val body: String? = null,
) {
    companion object {
        fun of(item: GithubProjectItemStateEvent, board: GithubBoard): GithubBoardItemResDto {
            val content = item.content
            return GithubBoardItemResDto(
                id = item.itemId,
                type = item.type,
                title = content?.title.orEmpty(),
                body = content?.body,
                number = content?.number,
                state = content?.state,
                url = content?.url,
                repository = content?.repository,
                assignees = content?.assignees.orEmpty(),
                labels = content?.labels.orEmpty().map { GithubBoardLabelResDto(it.name, it.color) },
                milestone = content?.milestone,
                draft = content?.draft ?: false,
                fields = item.fieldValues.map { value ->
                    val field = board.field(value.fieldId)
                    val display = when (value.dataType) {
                        "SINGLE_SELECT" -> field?.options?.firstOrNull { it.id == value.optionId }?.name
                        "ITERATION" -> field?.iterations?.firstOrNull { it.id == value.iterationId }?.title
                        "NUMBER" -> value.number?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
                        "DATE" -> value.date
                        else -> value.text
                    }
                    GithubBoardFieldValueResDto(
                        value.fieldId,
                        value.fieldName,
                        value.dataType,
                        display,
                        value.optionId,
                        value.iterationId,
                    )
                },
            )
        }
    }
}
