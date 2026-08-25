package com.cowork.project.domain.github.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub 이슈/PR 댓글")
data class GithubCommentResDto(
    @field:Schema(description = "댓글 ID", example = "1")
    val id: Long,
    @field:Schema(description = "작성자 GitHub 사용자명", example = "octocat")
    val author: String,
    @field:Schema(description = "댓글 본문", example = "확인했습니다, 반영하겠습니다.")
    val body: String,
    @field:Schema(description = "GitHub 댓글 URL", example = "https://github.com/my-org/my-repo/issues/1#issuecomment-1")
    val htmlUrl: String,
    @field:Schema(description = "생성 시각(ISO-8601)", example = "2026-06-23T02:30:31Z")
    val createdAt: String,
    @field:Schema(description = "수정 시각(ISO-8601)", example = "2026-06-23T02:30:31Z")
    val updatedAt: String,
)
