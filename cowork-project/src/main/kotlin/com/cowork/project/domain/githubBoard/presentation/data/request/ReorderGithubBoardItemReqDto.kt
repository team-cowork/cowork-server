package com.cowork.project.domain.githubBoard.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class ReorderGithubBoardItemReqDto(
    @param:Schema(description = "카드가 있는 Board 뷰 번호", example = "1", required = true)
    val viewNumber: Int,
    @param:Schema(
        description = "같은 컬럼에서 바로 앞에 둘 카드 ID. null은 컬럼 맨 위",
        example = "PVTI_lADOBk9Ru84AqHDWzgUcFQg",
        nullable = true,
    )
    val afterItemId: String?,
)
