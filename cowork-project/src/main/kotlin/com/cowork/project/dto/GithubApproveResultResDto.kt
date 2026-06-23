package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 승인 결과")
data class GithubApproveResultResDto(
    @field:Schema(description = "PR URL", example = "https://github.com/my-org/my-repo/pull/1")
    val prUrl: String,
    @field:Schema(description = "PR 번호", example = "1")
    val prNumber: Int,
)
