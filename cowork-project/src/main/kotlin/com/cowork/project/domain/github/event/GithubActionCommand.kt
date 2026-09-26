package com.cowork.project.domain.github.event

/**
 * cowork-github-app의 `CreateIssueDto`와 필드가 일치해야 한다.
 *
 * `channelId`/`teamId`/`requesterId`는 cowork-chat 슬래시 커맨드 경유 호출(`ChatGithubIssueCommandProcessor`)에서만
 * 채워지며, project REST API를 통한 직접 호출(`CreateGithubIssueServiceImpl`)에서는 결과 알림 대상이 없으므로 null로 둔다.
 */
data class GithubIssueCreateCommand(
    val owner: String,
    val repo: String,
    val title: String,
    val body: String?,
    val labels: List<String>,
    val channelId: Long? = null,
    val teamId: Long? = null,
    val requesterId: Long? = null,
)

/** cowork-github-app의 `PullRequestActionDto`와 필드가 일치해야 한다 (channelId/teamId는 optional이라 생략). */
data class GithubPullRequestActionCommand(
    val owner: String,
    val repo: String,
    val prNumber: Int,
    val requesterGithubUsername: String,
)
