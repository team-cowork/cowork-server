package com.cowork.preference.domain.teamrole.event

import com.cowork.preference.domain.teamrole.model.AccountTeamRole
import com.cowork.preference.domain.teamrole.model.TeamRoleDefinition
import com.cowork.preference.domain.teamrole.model.TeamRolePermissions
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceEventPayload.basePayload
import com.cowork.preference.global.messaging.event.PreferenceTopics
import io.vertx.core.json.JsonObject
import java.time.Instant

object TeamRoleEvents {
    fun roleUpserted(role: TeamRoleDefinition, occurredAt: Instant): PreferenceEvent = PreferenceEvent(
        topic = PreferenceTopics.TEAM_ROLE_TOPIC,
        key = "role:${role.teamId}:${role.id}",
        payload = basePayload("ROLE_UPSERTED", occurredAt)
            .put("teamId", role.teamId)
            .put("roleId", role.id)
            .put("name", role.name)
            .put("colorHex", role.colorHex)
            .put("priority", role.priority)
            .put("mentionable", role.mentionable)
            .put("isDefault", role.isDefault)
            .put("permissions", TeamRolePermissions.toJson(role.permissions)),
        occurredAt = occurredAt,
    )

    fun roleDeleted(teamId: Long, roleId: Long, occurredAt: Instant): PreferenceEvent = PreferenceEvent(
        topic = PreferenceTopics.TEAM_ROLE_TOPIC,
        key = "role:$teamId:$roleId",
        payload = basePayload("ROLE_DELETED", occurredAt)
            .put("teamId", teamId)
            .put("roleId", roleId),
        occurredAt = occurredAt,
    )

    fun assignmentUpserted(assignment: AccountTeamRole, occurredAt: Instant): PreferenceEvent =
        assignmentChanged("ASSIGNMENT_UPSERTED", assignment, occurredAt)

    fun assignmentDeleted(assignment: AccountTeamRole, occurredAt: Instant): PreferenceEvent =
        assignmentChanged("ASSIGNMENT_DELETED", assignment, occurredAt)

    fun memberAssignmentsDeleted(teamId: Long, accountId: Long, occurredAt: Instant): PreferenceEvent = PreferenceEvent(
        topic = PreferenceTopics.TEAM_ROLE_TOPIC,
        key = "member:$teamId:$accountId",
        payload = basePayload("MEMBER_ASSIGNMENTS_DELETED", occurredAt)
            .put("teamId", teamId)
            .put("accountId", accountId),
        occurredAt = occurredAt,
    )

    fun teamRoleCommandResult(operationId: String, result: JsonObject, occurredAt: Instant): PreferenceEvent =
        PreferenceEvent(
            topic = PreferenceTopics.TEAM_ROLE_COMMAND_RESULT_TOPIC,
            key = operationId,
            payload = result,
            occurredAt = occurredAt,
        )

    private fun assignmentChanged(
        eventType: String,
        assignment: AccountTeamRole,
        occurredAt: Instant,
    ): PreferenceEvent = PreferenceEvent(
        topic = PreferenceTopics.TEAM_ROLE_TOPIC,
        key = "assignment:${assignment.teamId}:${assignment.accountId}:${assignment.roleId}",
        payload = basePayload(eventType, occurredAt)
            .put("teamId", assignment.teamId)
            .put("accountId", assignment.accountId)
            .put("roleId", assignment.roleId),
        occurredAt = occurredAt,
    )
}
