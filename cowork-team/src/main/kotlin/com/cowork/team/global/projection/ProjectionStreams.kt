package com.cowork.team.global.projection

import com.cowork.team.domain.teamRole.operation.TeamRoleContractTopics
import com.cowork.team.global.consumer.Topics
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ProjectionStreams(
    @Value("\${KAFKA_GROUP_ID_TEAM_ROLE_PREFERENCE:cowork-team.team-role-projection}") consumerGroup: String,
    @Value("\${KAFKA_GROUP_ID_TEAM_SETTING_PREFERENCE:cowork-team.team-setting-projection}")
    teamSettingConsumerGroup: String,
) {
    val teamRole = ProjectionStream(consumerGroup, TeamRoleContractTopics.STATE, "cowork-preference")
    val teamSetting =
        ProjectionStream(teamSettingConsumerGroup, Topics.PREFERENCE_TEAM_SETTING_STATE, "cowork-preference")
    val required: Set<ProjectionStream> = setOf(teamRole, teamSetting)
}
