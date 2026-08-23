package com.cowork.project.domain.github.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub 이슈 라벨 적용/변경 요청 (전체 교체)")
data class UpdateGithubIssueLabelsReqDto(
    @param:Schema(description = "이슈에 적용할 라벨 전체 목록", example = "[\"bug\"]")
    val labels: List<String>,
)
