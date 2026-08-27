package com.cowork.team.global.projection

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ProjectionStreams(
    @Value("\${KAFKA_TOPIC_TEAM_ROLE_PREFERENCE:preference.team-role.changed}") topic: String,
    @Value("\${KAFKA_GROUP_ID_TEAM_ROLE_PREFERENCE:cowork-team.team-role-projection}") consumerGroup: String,
) {
    val teamRole = ProjectionStream(consumerGroup, topic, "cowork-preference")
    val required: Set<ProjectionStream> = setOf(teamRole)
}
