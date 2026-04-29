package com.cowork.project.consumer

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TeamLifecycleConsumer(
    private val handler: ProjectLifecycleHandler,
) {
    private val log = LoggerFactory.getLogger(TeamLifecycleConsumer::class.java)

    @KafkaListener(
        topics = [Topics.TEAM_LIFECYCLE],
        groupId = "cowork-project.team-lifecycle",
        containerFactory = "teamLifecycleListenerContainerFactory",
    )
    fun consume(payload: TeamLifecyclePayload) {
        when (payload.eventType) {
            "MEMBER_INVITED" -> handler.onMemberInvited(payload.teamId, payload.targetUserIds, "MEMBER")
            "ROLE_CHANGED" -> payload.targetUserIds.forEach {
                val newRole = payload.newRole ?: return@forEach
                handler.onRoleChanged(payload.teamId, it, newRole)
            }
            "MEMBER_REMOVED" -> payload.targetUserIds.forEach { handler.onMemberRemovedFromTeam(payload.teamId, it) }
            "TEAM_DELETED" -> handler.onTeamDeleted(payload.teamId)
            else -> log.warn("알 수 없는 team.lifecycle 이벤트 [eventType={}]", payload.eventType)
        }
    }
}
