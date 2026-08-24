package com.cowork.project.domain.github.client

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "cowork-github-app 이슈 라벨 전체 교체 요청")
data class GithubAppUpdateIssueLabelsReqDto(
    @param:Schema(description = "이슈에 적용할 라벨 전체 목록")
    val labels: List<String>,
)
