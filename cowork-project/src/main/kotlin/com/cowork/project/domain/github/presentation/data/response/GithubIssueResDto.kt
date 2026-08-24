package com.cowork.project.domain.github.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub 이슈 목록 요약 (보드 카드)")
data class GithubIssueResDto(
    @field:Schema(description = "이슈 번호", example = "1")
    val number: Int,
    @field:Schema(description = "이슈 제목", example = "로그인 실패 버그")
    val title: String,
    @field:Schema(description = "작성자 GitHub 사용자명", example = "octocat")
    val author: String,
    @field:Schema(description = "이슈 상태", example = "open")
    val state: String,
    @field:Schema(description = "GitHub 이슈 URL", example = "https://github.com/my-org/my-repo/issues/1")
    val htmlUrl: String,
    @field:Schema(description = "라벨 목록", example = "[\"bug\", \"needs-triage\"]")
    val labels: List<String>,
    @field:Schema(description = "생성 시각(ISO-8601)", example = "2026-06-23T02:30:31Z")
    val createdAt: String,
    @field:Schema(description = "수정 시각(ISO-8601)", example = "2026-06-23T02:30:31Z")
    val updatedAt: String,
)
