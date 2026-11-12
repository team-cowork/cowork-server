package com.cowork.preference.domain.access.projection.team.handler

import com.cowork.preference.domain.access.projection.team.model.TeamMemberCleanupScope
import com.cowork.preference.domain.access.projection.team.model.TeamMemberEvent
import com.cowork.preference.domain.access.projection.team.model.TeamMemberRecordDecision
import com.cowork.preference.domain.access.projection.team.parser.TeamMemberEventParser
import com.cowork.preference.domain.access.projection.team.policy.TeamMemberCleanupPolicy
import com.cowork.preference.domain.access.projection.team.repository.TeamMemberProjectionRepository
import com.cowork.preference.domain.projectrole.repository.ProjectAccessStateRepository
import com.cowork.preference.domain.teamrole.service.TeamRoleService
import com.cowork.preference.global.projection.checkpoint.ProjectionCheckpoint
import com.cowork.preference.global.projection.checkpoint.ProjectionCheckpointRepository
import com.cowork.preference.global.projection.consumer.ProjectionRecordHandler

class TeamMemberProjectionHandler(
    private val teamRoleService: TeamRoleService,
    private val teamMemberProjectionRepository: TeamMemberProjectionRepository,
    private val checkpointRepository: ProjectionCheckpointRepository,
    private val projectAccess: ProjectAccessStateRepository,
) : ProjectionRecordHandler {
    override suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String? =
        when (val decision = TeamMemberEventParser.parse(key, value)) {
            is TeamMemberRecordDecision.Apply -> {
                applyEvent(checkpoint, decision.event)
                null
            }

            is TeamMemberRecordDecision.Quarantine -> decision.reason
        }

    private suspend fun applyEvent(checkpoint: ProjectionCheckpoint, event: TeamMemberEvent) {
        checkpointRepository.inTransaction(checkpoint) { connection ->
            val applied = teamMemberProjectionRepository.apply(connection, event)
            if (event.eventType != "DELETE") {
                if (!applied) return@inTransaction
                val defaultCreated = teamRoleService.ensureDefaultRole(connection, event.teamId)
                projectAccess.refreshTeam(connection, event.teamId, if (defaultCreated) null else event.userId)
                return@inTransaction
            }
            when (TeamMemberCleanupPolicy.scope(event)) {
                TeamMemberCleanupScope.TEAM -> {
                    teamRoleService.deleteTeamRoles(connection, event.teamId, event.occurredAt)
                }

                TeamMemberCleanupScope.MEMBER -> {
                    teamRoleService.removeMemberRolesAtOrBefore(
                        connection,
                        event.userId,
                        event.teamId,
                        event.occurredAt,
                        publishMemberFence = applied,
                    )
                }
            }
            projectAccess.removeTeamAssignments(
                connection,
                event.teamId,
                if (TeamMemberCleanupPolicy.scope(event) == TeamMemberCleanupScope.TEAM) null else event.userId,
                cutoff = if (TeamMemberCleanupPolicy.scope(event) ==
                    TeamMemberCleanupScope.MEMBER
                ) {
                    event.occurredAt
                } else {
                    null
                },
            )
        }
    }
}
