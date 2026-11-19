package com.cowork.preference.domain.teamrole.service

import com.cowork.preference.domain.access.projection.team.model.TeamMemberEvent
import com.cowork.preference.domain.channelrole.event.ChannelRolePolicyEvents
import com.cowork.preference.domain.channelrole.repository.ChannelRolePolicyRepository
import com.cowork.preference.domain.teamrole.event.TeamRoleEvents
import com.cowork.preference.domain.teamrole.model.AccountTeamRole
import com.cowork.preference.domain.teamrole.repository.TeamRoleRepository
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.outbox.PreferenceOutboxRepository
import io.vertx.sqlclient.SqlClient
import java.time.Instant
import java.time.temporal.ChronoUnit

class TeamRoleService(
    private val repository: TeamRoleRepository,
    private val outboxRepository: PreferenceOutboxRepository,
    private val channelRolePolicyRepository: ChannelRolePolicyRepository,
) {

    internal suspend fun ensureDefaultRole(client: SqlClient, teamId: Long): Boolean {
        val role = repository.ensureDefaultRole(client, teamId) ?: return false
        outboxRepository.enqueue(
            client,
            TeamRoleEvents.roleUpserted(role, requireNotNull(role.updatedAt).toInstant()),
        )
        return true
    }

    internal suspend fun synchronizeMembershipPolicies(client: SqlClient, event: TeamMemberEvent) {
        for (owner in listOf(false, true)) {
            repository.ensureManagedPolicy(client, event.teamId, owner)?.let { role ->
                outboxRepository.enqueue(
                    client,
                    TeamRoleEvents.roleUpserted(role, requireNotNull(role.updatedAt).toInstant()),
                )
            }
        }
        // TODO(2026-11-17T06:59:44Z): Remove the migration ledger check and legacy ADMIN grant after all team
        // memberships are migrated.
        val firstMigration = repository.markPolicyMigrated(client, event.teamId, event.userId)
        val owner = repository.findManagedPolicy(client, event.teamId, true) ?: return
        val assignment = repository.findAssignment(client, event.userId, event.teamId, owner.id)
        // The membership owner flag is a source fact used to bind the root policy, never an authorization bypass.
        if (event.role == "OWNER") {
            if (assignment == null) grantManagedPolicy(client, event, owner.id)
        } else if (assignment != null) {
            val tombstone = repository.deleteAssignment(client, event.userId, event.teamId, owner.id, event.occurredAt)
            outboxRepository.enqueue(client, TeamRoleEvents.assignmentDeleted(assignment, tombstone.stateOccurredAt))
            repository.findManagedPolicy(client, event.teamId, false)?.let { grantManagedPolicy(client, event, it.id) }
        }
        if (firstMigration && event.role == "ADMIN") {
            repository.findManagedPolicy(client, event.teamId, false)?.let { grantManagedPolicy(client, event, it.id) }
        }
    }

    private suspend fun grantManagedPolicy(client: SqlClient, event: TeamMemberEvent, roleId: Long) {
        repository.assignRole(client, event.userId, event.teamId, roleId, event.occurredAt)
        val assignment = requireNotNull(repository.findAssignment(client, event.userId, event.teamId, roleId))
        outboxRepository.enqueue(
            client,
            TeamRoleEvents.assignmentUpserted(assignment, requireNotNull(assignment.updatedAt).toInstant()),
        )
    }

    internal suspend fun removeMemberRolesAtOrBefore(
        client: SqlClient,
        accountId: Long,
        teamId: Long,
        occurredAt: Instant,
        publishMemberFence: Boolean = true,
    ) {
        val removedAssignments = repository.removeMemberRolesAtOrBefore(client, accountId, teamId, occurredAt)
        val tombstoneVersion = nextProjectionVersion(
            sequenceOf(occurredAt) + removedAssignments.asSequence().mapNotNull { it.updatedAt?.toInstant() },
        )
        // A delayed leave must not fence assignments granted after the member rejoined.
        val memberFence = if (publishMemberFence) {
            repository.upsertMemberFence(
                client,
                teamId,
                accountId,
                tombstoneVersion,
            )
        } else {
            null
        }
        val assignmentTombstones = removedAssignments.map { assignment ->
            repository.deleteAssignment(
                client,
                assignment.accountId,
                assignment.teamId,
                assignment.roleId,
                memberFence?.stateOccurredAt ?: tombstoneVersion,
            )
        }
        outboxRepository.enqueueAll(
            client,
            assignmentTombstones.map { tombstone ->
                TeamRoleEvents.assignmentDeleted(
                    AccountTeamRole(tombstone.accountId, tombstone.teamId, tombstone.roleId),
                    tombstone.stateOccurredAt,
                )
            } +
                listOfNotNull(
                    memberFence?.let {
                        TeamRoleEvents.memberAssignmentsDeleted(teamId, accountId, it.stateOccurredAt)
                    },
                ),
        )
    }

    internal suspend fun deleteTeamRoles(client: SqlClient, teamId: Long, occurredAt: Instant) {
        repository.markTeamDeleted(client, teamId, occurredAt)
        val assignments = repository.findMemberRoles(client, teamId)
        val roles = repository.findRoles(client, teamId)
        val policyEvents = deleteChannelRolePoliciesForTeam(client, teamId)
        val memberFences = assignments.groupBy(AccountTeamRole::accountId).mapValues { (accountId, memberAssignments) ->
            val minimum = nextProjectionVersion(
                sequenceOf(occurredAt) + memberAssignments.asSequence().mapNotNull { it.updatedAt?.toInstant() },
            )
            repository.upsertMemberFence(client, teamId, accountId, minimum)
        }
        val assignmentTombstones = assignments.map { assignment ->
            repository.deleteAssignment(
                client,
                assignment.accountId,
                assignment.teamId,
                assignment.roleId,
                requireNotNull(memberFences[assignment.accountId]).stateOccurredAt,
            )
        }
        val roleTombstones = roles.map { role ->
            repository.deleteRoleWithTombstone(client, teamId, role.id, occurredAt)
        }
        outboxRepository.enqueueAll(
            client,
            buildList {
                addAll(policyEvents)
                assignmentTombstones.mapTo(this) { tombstone ->
                    TeamRoleEvents.assignmentDeleted(
                        AccountTeamRole(tombstone.accountId, tombstone.teamId, tombstone.roleId),
                        tombstone.stateOccurredAt,
                    )
                }
                memberFences.values.mapTo(this) { fence ->
                    TeamRoleEvents.memberAssignmentsDeleted(fence.teamId, fence.accountId, fence.stateOccurredAt)
                }
                roleTombstones.mapTo(this) { tombstone ->
                    TeamRoleEvents.roleDeleted(tombstone.teamId, tombstone.roleId, tombstone.stateOccurredAt)
                }
            },
        )
    }

    private suspend fun deleteChannelRolePoliciesForTeam(client: SqlClient, teamId: Long) =
        channelRolePolicyRepository.findPoliciesByTeam(client, teamId).map { policy ->
            deleteChannelRolePolicy(client, policy.teamId, policy.channelId, policy.roleId)
        }

    private suspend fun deleteChannelRolePolicy(
        client: SqlClient,
        teamId: Long,
        channelId: Long,
        roleId: Long,
    ): PreferenceEvent {
        val tombstone = channelRolePolicyRepository.deletePolicy(client, teamId, channelId, roleId)
        return ChannelRolePolicyEvents.channelRolePolicyState(
            teamId = tombstone.teamId,
            channelId = tombstone.channelId,
            roleId = tombstone.roleId,
            permissions = null,
            occurredAt = tombstone.stateOccurredAt,
            snapshot = false,
        )
    }

    private fun nextProjectionVersion(versions: Sequence<Instant>): Instant = requireNotNull(versions.maxOrNull())
        .truncatedTo(ChronoUnit.MICROS)
        .plus(1, ChronoUnit.MICROS)
}
