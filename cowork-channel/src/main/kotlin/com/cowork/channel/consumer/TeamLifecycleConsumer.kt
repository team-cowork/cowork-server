package com.cowork.channel.consumer

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TeamLifecycleConsumer(
    private val handler: ChannelLifecycleHandler,
) {
    private val log = LoggerFactory.getLogger(TeamLifecycleConsumer::class.java)

    @KafkaListener(
        topics = [Topics.TEAM_LIFECYCLE],
        groupId = "cowork-channel.team-lifecycle",
        containerFactory = "teamLifecycleListenerContainerFactory",
    )
    fun consume(payload: TeamLifecyclePayload) {
        when (payload.eventType) {
            "TEAM_DELETED" -> handler.onTeamDeleted(payload.teamId)
            "MEMBER_REMOVED" -> payload.targetUserIds.forEach { handler.onMemberRemovedFromTeam(payload.teamId, it) }
            "MEMBER_INVITED", "ROLE_CHANGED" ->
                log.info("team.lifecycle 수신 [eventType={}, teamId={}]", payload.eventType, payload.teamId)
            else -> log.warn("알 수 없는 team.lifecycle 이벤트 [eventType={}]", payload.eventType)
        }
    }
}
