package com.cowork.channel.global.projection

import com.cowork.channel.global.consumer.Topics
import org.springframework.stereotype.Component

@Component
class ProjectionStreams {
    val project = ProjectionStream("cowork-channel.project-event", Topics.PROJECT_EVENT, "cowork-project")
    val teamMember = ProjectionStream("cowork-channel.team-member", Topics.TEAM_MEMBER_EVENT, "cowork-team")
    val teamLifecycle = ProjectionStream("cowork-channel.team-lifecycle", Topics.TEAM_LIFECYCLE, "cowork-team")
    val userLifecycle = ProjectionStream("cowork-channel.user-lifecycle", Topics.USER_LIFECYCLE, null)

    // user.lifecycle currently has no source snapshot producer. It remains a
    // durable action consumer, but must not masquerade as snapshot-backed state.
    val required: Set<ProjectionStream> = setOf(project, teamMember, teamLifecycle)
}
