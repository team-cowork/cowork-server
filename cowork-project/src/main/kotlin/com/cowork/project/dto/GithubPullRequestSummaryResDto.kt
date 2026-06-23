package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 목록 요약 (보드 카드)")
data class GithubPullRequestSummaryResDto(
    @field:Schema(description = "PR 번호", example = "1")
    val number: Int,
    @field:Schema(description = "PR 제목", example = "feat: add login")
    val title: String,
    @field:Schema(description = "작성자 GitHub 사용자명", example = "octocat")
    val author: String,
    @field:Schema(description = "PR 상태", example = "open")
    val state: String,
    @field:Schema(description = "드래프트 여부")
    val draft: Boolean,
    @field:Schema(description = "머지 여부")
    val merged: Boolean,
    @field:Schema(description = "GitHub PR URL", example = "https://github.com/my-org/my-repo/pull/1")
    val htmlUrl: String,
    @field:Schema(description = "라벨 목록", example = "[\"bug\", \"enhancement\"]")
    val labels: List<String>,
    @field:Schema(description = "생성 시각(ISO-8601)", example = "2026-06-23T02:30:31Z")
    val createdAt: String,
    @field:Schema(description = "수정 시각(ISO-8601)", example = "2026-06-23T02:30:31Z")
    val updatedAt: String,
)
