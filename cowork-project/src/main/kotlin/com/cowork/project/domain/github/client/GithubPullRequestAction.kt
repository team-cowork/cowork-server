package com.cowork.project.domain.github.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** cowork-github의 `PullRequestActionRequestDto`와 필드가 일치해야 한다. */
data class GithubPullRequestActionRequest(val requesterGithubUsername: String)

/** cowork-github의 PR 머지·승인·닫기 응답. 머지 응답의 `alreadyMerged`는 HTTP에서 항상 false라 읽지 않는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPullRequestActionResult(val prNumber: Int, val prUrl: String)
