package com.cowork.project.global.projection

import com.cowork.project.global.consumer.Topics
import org.springframework.stereotype.Component

private const val TEAM_MEMBER_EVENT_TOPIC = "team.member.event"

@Component
class ProjectionStreams {
    val teamMember = ProjectionStream("cowork-project.team-member", TEAM_MEMBER_EVENT_TOPIC, "cowork-team")
    val teamLifecycle = ProjectionStream("cowork-project.team-lifecycle", Topics.TEAM_LIFECYCLE, "cowork-team")
    val userLifecycle = ProjectionStream("cowork-project.user-lifecycle", Topics.USER_LIFECYCLE, null)

    // user.lifecycle currently has no source snapshot producer. It remains a
    // durable action consumer, but must not masquerade as snapshot-backed state.
    val required: Set<ProjectionStream> = setOf(teamMember, teamLifecycle)
}
