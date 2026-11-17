package com.cowork.project.domain.githubBoard.presentation.data.response

import com.cowork.project.domain.githubBoard.model.GithubBoard
import com.cowork.project.domain.githubBoard.model.GithubBoardFilterCondition
import io.swagger.v3.oas.annotations.media.Schema

data class GithubBoardFilterConditionResDto(
    @field:Schema(description = "원래 filter 토큰", example = "-status:Done")
    val raw: String,
    @field:Schema(
        description = "조건 종류",
        example = "field",
        allowableValues = ["assignee", "label", "repo", "milestone", "title", "text", "is", "has", "field"],
    )
    val qualifier: String,
    @field:Schema(description = "OR로 비교하는 값", example = "[\"Done\"]")
    val values: List<String>,
    @field:Schema(description = "부정 조건 여부", example = "true")
    val negated: Boolean,
    @field:Schema(description = "qualifier가 field이면 대상 field 이름", example = "Status", nullable = true)
    val fieldName: String?,
)

data class GithubBoardAppliedFilterResDto(
    @field:Schema(description = "뷰에 저장된 filter 문자열", example = "is:open -status:Done", nullable = true)
    val raw: String?,
    @field:Schema(description = "뷰 filter 미적용 여부(ignoreViewFilter)", example = "false")
    val ignored: Boolean,
    @field:Schema(description = "적용한 조건(AND)")
    val conditions: List<GithubBoardFilterConditionResDto>,
) {
    companion object {
        fun of(raw: String?, ignored: Boolean, conditions: List<GithubBoardFilterCondition>, board: GithubBoard) =
            GithubBoardAppliedFilterResDto(
                raw = raw,
                ignored = ignored,
                conditions = conditions.map { condition ->
                    GithubBoardFilterConditionResDto(
                        raw = condition.raw,
                        qualifier = condition.qualifier,
                        // has 조건은 field ID로 저장하므로 응답에서는 field 이름으로 바꾼다.
                        values = if (condition.qualifier == "has") {
                            condition.values.map { value -> board.field(value)?.name ?: value }
                        } else {
                            condition.values
                        },
                        negated = condition.negated,
                        fieldName = condition.fieldId?.let { board.field(it)?.name },
                    )
                },
            )
    }
}
