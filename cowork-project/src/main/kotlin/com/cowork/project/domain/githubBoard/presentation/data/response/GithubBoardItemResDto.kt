package com.cowork.project.domain.githubBoard.presentation.data.response

import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import com.cowork.project.domain.githubBoard.model.GithubBoard
import io.swagger.v3.oas.annotations.media.Schema

data class GithubBoardLabelResDto(
    @field:Schema(description = "라벨 이름", example = "bug")
    val name: String,
    @field:Schema(description = "라벨 색상 hex, # 제외", example = "d73a4a")
    val color: String,
)

data class GithubBoardFieldValueResDto(
    @field:Schema(description = "field ID", example = "PVTSSF_lADOBk9Ru84AqHDWzgg")
    val fieldId: String,
    @field:Schema(description = "field 이름", example = "Status")
    val fieldName: String,
    @field:Schema(
        description = "값 종류",
        example = "SINGLE_SELECT",
        allowableValues = ["TEXT", "NUMBER", "DATE", "SINGLE_SELECT", "ITERATION"],
    )
    val dataType: String,
    @field:Schema(
        description = "표시 값. 옵션 이름, iteration 제목, 텍스트, 숫자 또는 날짜",
        example = "In Progress",
        nullable = true,
    )
    val displayValue: String?,
    @field:Schema(description = "single-select 옵션 ID", nullable = true)
    val optionId: String?,
    @field:Schema(description = "iteration ID", nullable = true)
    val iterationId: String?,
)

data class GithubBoardItemResDto(
    @field:Schema(description = "GitHub Project item ID", example = "PVTI_lADOBk9Ru84AqHDWzgUcFQg")
    val id: String,
    @field:Schema(
        description = "item 종류",
        example = "ISSUE",
        allowableValues = ["ISSUE", "PULL_REQUEST", "DRAFT_ISSUE", "REDACTED"],
    )
    val type: String,
    @field:Schema(description = "제목. REDACTED item은 빈 문자열", example = "로그인 오류 수정")
    val title: String,
    @field:Schema(description = "이슈나 PR 번호", example = "42", nullable = true)
    val number: Int?,
    @field:Schema(description = "상태", example = "OPEN", nullable = true, allowableValues = ["OPEN", "CLOSED", "MERGED"])
    val state: String?,
    @field:Schema(description = "GitHub URL", nullable = true)
    val url: String?,
    @field:Schema(description = "저장소(owner/repo)", example = "my-org/app", nullable = true)
    val repository: String?,
    @field:Schema(description = "담당자 GitHub 사용자 이름")
    val assignees: List<String>,
    @field:Schema(description = "라벨")
    val labels: List<GithubBoardLabelResDto>,
    @field:Schema(description = "마일스톤 제목", nullable = true)
    val milestone: String?,
    @field:Schema(description = "draft 이슈 또는 draft PR 여부", example = "false")
    val draft: Boolean,
    @field:Schema(description = "Project field 값")
    val fields: List<GithubBoardFieldValueResDto>,
) {
    companion object {
        fun of(item: GithubProjectItemStateEvent, board: GithubBoard): GithubBoardItemResDto {
            val content = item.content
            return GithubBoardItemResDto(
                id = item.itemId,
                type = item.type,
                title = content?.title.orEmpty(),
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
