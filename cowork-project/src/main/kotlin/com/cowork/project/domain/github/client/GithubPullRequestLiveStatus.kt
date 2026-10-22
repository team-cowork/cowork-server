package com.cowork.project.domain.github.client

/** GitHub이 요청 시점마다 재계산해 projection으로 재구성할 수 없는 PR 상태. */
data class GithubPullRequestLiveStatus(
    val mergeable: Boolean?,
    val mergeableState: String,
    val reviewDecision: String?,
)
