package com.cowork.project.domain.github.event

/** cowork-github-app의 `CreateIssueDto`와 필드가 일치해야 한다 (channelId/teamId는 optional이라 생략). */
data class GithubIssueCreateCommand(
    val owner: String,
    val repo: String,
    val title: String,
    val body: String?,
    val labels: List<String>,
)

/** cowork-github-app의 `PullRequestActionDto`와 필드가 일치해야 한다 (channelId/teamId는 optional이라 생략). */
data class GithubPullRequestActionCommand(
    val owner: String,
    val repo: String,
    val prNumber: Int,
    val requesterGithubUsername: String,
)
