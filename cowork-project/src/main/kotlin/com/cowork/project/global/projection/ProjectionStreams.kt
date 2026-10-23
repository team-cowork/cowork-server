package com.cowork.project.global.projection

import com.cowork.project.domain.github.event.GITHUB_ISSUE_COMMENT_STATE_TOPIC
import com.cowork.project.domain.github.event.GITHUB_ISSUE_STATE_TOPIC
import com.cowork.project.domain.github.event.GITHUB_LABEL_STATE_TOPIC
import com.cowork.project.domain.github.event.GITHUB_PULL_REQUEST_STATE_TOPIC
import com.cowork.project.domain.github.event.GITHUB_REPO_STATE_TOPIC
import com.cowork.project.global.consumer.Topics
import org.springframework.stereotype.Component

private const val TEAM_MEMBER_EVENT_TOPIC = "team.member.event"

@Component
class ProjectionStreams {
    val channelState = ProjectionStream(
        "cowork-project.channel-state",
        ProjectionTopics.CHANNEL_STATE,
        "cowork-channel",
    )
    val teamMember = ProjectionStream("cowork-project.team-member", TEAM_MEMBER_EVENT_TOPIC, "cowork-team")
    val teamLifecycle = ProjectionStream("cowork-project.team-lifecycle", Topics.TEAM_LIFECYCLE, "cowork-team")
    val userProfile = ProjectionStream(
        "cowork-project.user-profile",
        ProjectionTopics.USER_PROFILE,
        "cowork-user",
    )
    val githubRepoSetting = ProjectionStream(
        "cowork-project.github-repo-setting",
        ProjectionTopics.GITHUB_REPO_SETTING_STATE,
        "cowork-preference",
    )

    // GitHub state는 cowork-github가 대상별 동기화 완료 마커를 남기므로 전역 readiness에 넣지 않고,
    // 조회할 때 GithubSyncMarkers로 대상 단위로 판단한다. 체크포인트와 격리만 이 프레임워크를 쓴다.
    val githubRepoState = ProjectionStream("cowork-project.github-repo-state", GITHUB_REPO_STATE_TOPIC, null)
    val githubIssueState = ProjectionStream("cowork-project.github-issue-state", GITHUB_ISSUE_STATE_TOPIC, null)
    val githubPullRequestState = ProjectionStream(
        "cowork-project.github-pull-request-state",
        GITHUB_PULL_REQUEST_STATE_TOPIC,
        null,
    )
    val githubLabelState = ProjectionStream("cowork-project.github-label-state", GITHUB_LABEL_STATE_TOPIC, null)
    val githubIssueCommentState = ProjectionStream(
        "cowork-project.github-issue-comment-state",
        GITHUB_ISSUE_COMMENT_STATE_TOPIC,
        null,
    )

    val required: Set<ProjectionStream> = setOf(
        channelState,
        teamMember,
        teamLifecycle,
        userProfile,
        githubRepoSetting,
    )
}
