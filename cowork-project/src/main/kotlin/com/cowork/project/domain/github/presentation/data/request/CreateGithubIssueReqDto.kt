package com.cowork.project.domain.github.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub 이슈 생성 요청")
data class CreateGithubIssueReqDto(
    @param:Schema(description = "이슈 제목", example = "로그인 실패 버그")
    val title: String,
    @param:Schema(description = "이슈 본문", example = "로그인 시 500 에러가 발생합니다.")
    val body: String?,
    @param:Schema(description = "생성과 동시에 적용할 라벨 (선택)", example = "bug")
    val label: String?,
)
