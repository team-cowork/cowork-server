package com.cowork.project.domain.github.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub 댓글 수정 요청")
data class UpdateGithubCommentReqDto(
    @param:Schema(description = "댓글 본문", example = "확인했습니다, 반영하겠습니다.")
    val body: String,
)
