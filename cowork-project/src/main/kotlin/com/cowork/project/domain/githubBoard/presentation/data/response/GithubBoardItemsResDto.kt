package com.cowork.project.domain.githubBoard.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

data class GithubBoardItemsResDto(
    @field:Schema(description = "조회한 뷰")
    val view: GithubBoardViewResDto,
    @field:Schema(description = "조건에 맞는 item. 뷰 정렬 순서이며 정렬이 없으면 GitHub 위치 순서")
    val items: List<GithubBoardItemResDto>,
    @field:Schema(description = "서버가 해석해 적용한 뷰 filter")
    val appliedFilter: GithubBoardAppliedFilterResDto,
    @field:Schema(description = "해석하지 못해 적용하지 않은 뷰 filter 토큰", example = "[\"reason:completed\"]")
    val unsupportedFilterTokens: List<String>,
)
