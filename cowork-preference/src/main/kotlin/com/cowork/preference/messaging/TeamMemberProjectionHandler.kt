package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.service.TeamRoleService

class TeamMemberProjectionHandler(
    private val teamRoleService: TeamRoleService,
    private val teamMemberProjectionRepository: TeamMemberProjectionRepository,
    private val checkpointRepository: ProjectionCheckpointRepository,
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
            if (!applied || event.eventType != "DELETE") return@inTransaction
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
                    )
                }
            }
        }
    }
}
