package com.cowork.channel.global.projection

import com.cowork.channel.global.consumer.Topics
import org.springframework.stereotype.Component

@Component
class ProjectionStreams {
    val project = ProjectionStream("cowork-channel.project-event", Topics.PROJECT_EVENT, "cowork-project")
    val teamMember = ProjectionStream("cowork-channel.team-member", Topics.TEAM_MEMBER_EVENT, "cowork-team")
    val teamLifecycle = ProjectionStream("cowork-channel.team-lifecycle", Topics.TEAM_LIFECYCLE, "cowork-team")
    val teamRole = ProjectionStream(
        "cowork-channel.team-role-projection",
        Topics.PREFERENCE_TEAM_ROLE_CHANGED,
        "cowork-preference",
    )
    val channelRolePolicy = ProjectionStream(
        "cowork-channel.channel-role-policy-projection",
        Topics.CHANNEL_ROLE_POLICY_CHANGED,
        "cowork-preference",
    )
    val required: Set<ProjectionStream> = setOf(project, teamMember, teamLifecycle, teamRole, channelRolePolicy)
}
