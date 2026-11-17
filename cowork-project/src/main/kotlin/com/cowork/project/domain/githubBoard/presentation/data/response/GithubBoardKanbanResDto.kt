package com.cowork.project.domain.githubBoard.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

data class GithubBoardColumnFieldResDto(
    @field:Schema(description = "컬럼 field ID")
    val id: String,
    @field:Schema(description = "컬럼 field 이름", example = "Status")
    val name: String,
    @field:Schema(
        description = "컬럼 field 종류",
        example = "SINGLE_SELECT",
        allowableValues = ["SINGLE_SELECT", "ITERATION"],
    )
    val dataType: String,
)

data class GithubBoardColumnResDto(
    @field:Schema(description = "컬럼 ID. 옵션 ID 또는 iteration ID이며 null은 값 없는 컬럼", nullable = true)
    val id: String?,
    @field:Schema(description = "컬럼 이름", example = "In Progress")
    val name: String,
    @field:Schema(description = "옵션 색상", example = "YELLOW", nullable = true)
    val color: String?,
    @field:Schema(description = "컬럼 안 카드 수", example = "3")
    val itemCount: Int,
    @field:Schema(description = "컬럼 안 카드. 위에서 아래 순서")
    val items: List<GithubBoardItemResDto>,
)

data class GithubBoardKanbanResDto(
    @field:Schema(description = "조회한 Board 뷰")
    val view: GithubBoardViewResDto,
    @field:Schema(description = "컬럼을 나누는 field")
    val columnField: GithubBoardColumnFieldResDto,
    @field:Schema(description = "컬럼. 값 없는 컬럼이 맨 앞이고 이후 옵션 순서이며 iteration은 시작일 순서")
    val columns: List<GithubBoardColumnResDto>,
    @field:Schema(description = "서버가 해석해 적용한 뷰 filter")
    val appliedFilter: GithubBoardAppliedFilterResDto,
    @field:Schema(description = "해석하지 못해 적용하지 않은 뷰 filter 토큰", example = "[\"reason:completed\"]")
    val unsupportedFilterTokens: List<String>,
)
