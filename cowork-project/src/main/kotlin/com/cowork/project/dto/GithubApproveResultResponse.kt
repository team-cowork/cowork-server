package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 승인 결과")
data class GithubApproveResultResponse(
    val prUrl: String,
    val prNumber: Int,
)
