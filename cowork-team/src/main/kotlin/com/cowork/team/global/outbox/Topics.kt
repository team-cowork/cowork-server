package com.cowork.team.global.outbox

import org.springframework.modulith.NamedInterface

@NamedInterface("outbox", propagate = false)
object Topics {
    const val NOTIFICATION_TRIGGER = "notification.trigger"
    const val TEAM_LIFECYCLE = "team.lifecycle"
    const val TEAM_MEMBER_EVENT = "team.member.event"
}
