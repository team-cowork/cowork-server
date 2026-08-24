package com.cowork.project.domain.github.client

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "cowork-github-app 이슈 생성 요청")
data class GithubAppCreateIssueReqDto(
    @param:Schema(description = "이슈 제목")
    val title: String,
    @param:Schema(description = "이슈 본문")
    val body: String?,
    @param:Schema(description = "생성과 동시에 적용할 라벨 목록")
    val labels: List<String>,
)
