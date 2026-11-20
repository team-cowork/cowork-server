package com.cowork.project.domain.githubBoard.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

data class GithubBoardSortResDto(
    @field:Schema(description = "정렬 field")
    val field: GithubBoardFieldResDto,
    @field:Schema(description = "정렬 방향", example = "ASC", allowableValues = ["ASC", "DESC"])
    val direction: String,
)

data class GithubBoardGroupResDto(
    @field:Schema(
        description = "그룹 ID. 옵션 ID, iteration ID 또는 field 값입니다. null은 값 없는 그룹이거나 그룹 기준이 없을 때의 전체 그룹입니다.",
        nullable = true,
    )
    val id: String?,
    @field:Schema(description = "그룹 이름", example = "In Progress")
    val name: String,
    @field:Schema(description = "옵션 색상", example = "YELLOW", nullable = true)
    val color: String?,
    @field:Schema(description = "그룹 안 카드 수", example = "3")
    val itemCount: Int,
    @field:Schema(description = "그룹 안 카드. 뷰 정렬 순서이며 정렬이 없으면 GitHub 위치 순서")
    val items: List<GithubBoardItemResDto>,
)

data class GithubBoardTableResDto(
    @field:Schema(description = "조회한 Table 뷰")
    val view: GithubBoardViewResDto,
    @field:Schema(description = "뷰에 저장된 표시 field. 열 순서")
    val visibleFields: List<GithubBoardFieldResDto>,
    @field:Schema(description = "뷰에 저장된 정렬. 앞의 정렬이 우선")
    val sortBy: List<GithubBoardSortResDto>,
    @field:Schema(description = "뷰에 저장된 그룹 field", nullable = true)
    val groupByField: GithubBoardFieldResDto?,
    @field:Schema(
        description = "그룹 field로 나눌 수 있는지 여부. 담당자나 라벨처럼 값이 여러 개인 field이면 false이며 모든 카드를 그룹 하나에 담습니다.",
        example = "true",
    )
    val groupSupported: Boolean,
    @field:Schema(
        description = "그룹. 값 없는 그룹이 맨 앞이고 이후 옵션 순서, iteration 시작일 순서 또는 값의 오름차순입니다. 그룹 field가 없으면 모든 카드를 담은 그룹 하나입니다.",
    )
    val groups: List<GithubBoardGroupResDto>,
    @field:Schema(description = "서버가 해석해 적용한 뷰 filter")
    val appliedFilter: GithubBoardAppliedFilterResDto,
    @field:Schema(description = "해석하지 못해 적용하지 않은 뷰 filter 토큰", example = "[\"reason:completed\"]")
    val unsupportedFilterTokens: List<String>,
)
