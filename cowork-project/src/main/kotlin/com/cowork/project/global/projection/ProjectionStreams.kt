package com.cowork.project.global.projection

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

    val required: Set<ProjectionStream> = setOf(
        channelState,
        teamMember,
        teamLifecycle,
        userProfile,
        githubRepoSetting,
    )
}
