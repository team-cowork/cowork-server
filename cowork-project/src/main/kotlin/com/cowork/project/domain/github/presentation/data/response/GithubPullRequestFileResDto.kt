package com.cowork.project.domain.github.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 파일 변경")
data class GithubPullRequestFileResDto(
    @field:Schema(description = "파일명", example = "src/main/kotlin/App.kt")
    val filename: String,
    @field:Schema(description = "변경 상태", example = "modified")
    val status: String,
    @field:Schema(description = "추가된 라인 수", example = "10")
    val additions: Int,
    @field:Schema(description = "삭제된 라인 수", example = "2")
    val deletions: Int,
    @field:Schema(description = "diff patch")
    val patch: String?,
)
