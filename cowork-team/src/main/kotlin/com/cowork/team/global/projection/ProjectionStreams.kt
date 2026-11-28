package com.cowork.team.global.projection

import com.cowork.team.global.PROJECT_CALENDAR_EVENT_STATE_TOPIC
import com.cowork.team.global.PROJECT_READABLE_USER_STATE_TOPIC
import com.cowork.team.global.consumer.TeamAccessState
import com.cowork.team.global.consumer.TeamRoleContractTopics
import com.cowork.team.global.consumer.Topics
import com.cowork.team.global.policy.AccountPolicyProjection
import org.springframework.beans.factory.annotation.Value
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

@Component
@NamedInterface("projection", propagate = false)
class ProjectionStreams(
    @Value($$"${KAFKA_GROUP_ID_TEAM_ROLE_PREFERENCE:cowork-team.team-role-projection}") consumerGroup: String,
    @Value($$"${KAFKA_GROUP_ID_TEAM_SETTING_PREFERENCE:cowork-team.team-setting-projection}")
    teamSettingConsumerGroup: String,
) {
    val teamAccess = ProjectionStream(TeamAccessState.GROUP, TeamAccessState.TOPIC, "cowork-preference")
    val teamRole = ProjectionStream(consumerGroup, TeamRoleContractTopics.STATE, "cowork-preference")
    val teamSetting =
        ProjectionStream(teamSettingConsumerGroup, Topics.PREFERENCE_TEAM_SETTING_STATE, "cowork-preference")
    val accountRole =
        ProjectionStream(AccountPolicyProjection.GROUP, AccountPolicyProjection.TOPIC, "cowork-preference")
    val required: Set<ProjectionStream> = setOf(teamAccess, teamRole, teamSetting, accountRole)

    val projectCalendarEvent =
        ProjectionStream(PROJECT_CALENDAR_EVENT_GROUP, PROJECT_CALENDAR_EVENT_STATE_TOPIC, "cowork-project")
    val projectReadableUser =
        ProjectionStream(PROJECT_READABLE_USER_GROUP, PROJECT_READABLE_USER_STATE_TOPIC, "cowork-project")

    /**
     * 팀 캘린더 통합 조회만 쓰는 cowork-project 상태. 서비스 readiness에 넣지 않으므로 cowork-project가 늦어도
     * 다른 팀 API는 동작하고, 통합 조회만 따라잡을 때까지 `503`을 돌려준다.
     */
    val calendar: Set<ProjectionStream> = setOf(projectCalendarEvent, projectReadableUser)

    companion object {
        const val PROJECT_CALENDAR_EVENT_GROUP = "cowork-team.project-calendar-event-projection"
        const val PROJECT_READABLE_USER_GROUP = "cowork-team.project-readable-user-projection"
    }
}
