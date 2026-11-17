package com.cowork.project.domain.githubBoard.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class MoveGithubBoardItemReqDto(
    @param:Schema(description = "카드를 옮길 Board 뷰 번호", example = "1", required = true)
    val viewNumber: Int,
    @param:Schema(
        description = "대상 컬럼 ID. 옵션 ID 또는 iteration ID이며 null은 값 없는 컬럼",
        example = "98236657",
        nullable = true,
    )
    val columnId: String?,
    @param:Schema(
        description = "대상 컬럼에서 바로 앞에 둘 카드 ID. null은 컬럼 맨 위",
        example = "PVTI_lADOBk9Ru84AqHDWzgUcFQg",
        nullable = true,
    )
    val afterItemId: String?,
)
