package com.cowork.project.global.consumer

object Topics {
    const val TEAM_LIFECYCLE = "team.lifecycle"

    /** cowork-github-app이 실제로 소비한다 (cowork-chat 슬래시 커맨드도 같은 토픽을 쓴다). */
    const val GITHUB_ISSUE_CREATE = "github.issue.create"

    /** cowork-github-app이 실제로 소비한다. channelId/teamId가 없으면 결과 발행은 생략된다. */
    const val GITHUB_PR_MERGE = "github.pr.merge"

    /** cowork-github-app이 실제로 소비한다. channelId/teamId가 없으면 결과 발행은 생략된다. */
    const val GITHUB_PR_APPROVE = "github.pr.approve"
}
