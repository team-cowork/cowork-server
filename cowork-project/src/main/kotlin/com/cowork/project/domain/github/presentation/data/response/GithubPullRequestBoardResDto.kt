package com.cowork.project.domain.github.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 진행 중 PR 칸반 보드. upstream을 `state=open`으로 조회해 컬럼별로 그룹핑한다.
 * 머지·종료 컬럼은 후속 과제(`state=all` 또는 별도 조회)로 확장한다.
 */
@Schema(description = "진행 중인 PR을 상태별로 나눈 목록")
data class GithubPullRequestBoardResDto(
    @field:Schema(description = "초안 PR 목록")
    val draft: List<GithubPullRequestSummaryResDto>,
    @field:Schema(description = "열린 PR 중 초안이 아닌 PR 목록")
    val inReview: List<GithubPullRequestSummaryResDto>,
)
