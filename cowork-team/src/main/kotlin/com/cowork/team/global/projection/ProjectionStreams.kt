package com.cowork.team.global.projection

import com.cowork.team.domain.teamRole.operation.TeamRoleContractTopics
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ProjectionStreams(
    @Value("\${KAFKA_GROUP_ID_TEAM_ROLE_PREFERENCE:cowork-team.team-role-projection}") consumerGroup: String,
) {
    val teamRole = ProjectionStream(consumerGroup, TeamRoleContractTopics.STATE, "cowork-preference")
    val required: Set<ProjectionStream> = setOf(teamRole)
}
