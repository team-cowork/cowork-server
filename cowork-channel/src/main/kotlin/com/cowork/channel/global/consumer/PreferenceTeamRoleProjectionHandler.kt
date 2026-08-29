package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstone
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstoneRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import com.cowork.channel.global.projection.toProjectionPrecision
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PreferenceTeamRoleProjectionHandler(
    private val roleRepository: TeamRoleProjectionRepository,
    private val assignmentRepository: TeamRoleAssignmentProjectionRepository,
    private val memberTombstoneRepository: TeamRoleMemberTombstoneRepository,
) {
    @Transactional
    fun handle(sourceEvent: PreferenceTeamRoleChangedEvent) {
        val event = sourceEvent.copy(occurredAt = sourceEvent.occurredAt.toProjectionPrecision())
        when (event.eventType) {
            "ROLE_UPSERTED" -> upsertRole(event)
            "ROLE_DELETED" -> deleteRole(event)
            "ASSIGNMENT_UPSERTED" -> upsertAssignment(event)
            "ASSIGNMENT_DELETED" -> deleteAssignment(event)
            "MEMBER_ASSIGNMENTS_DELETED" -> deleteMemberAssignments(event)
        }
    }

    private fun upsertRole(event: PreferenceTeamRoleChangedEvent) {
        val roleId = requireNotNull(event.roleId)
        val existing = roleRepository.findById(roleId).orElse(null)
        val existingVersion = existing?.sourceOccurredAt?.toProjectionPrecision()
        if (existingVersion?.isAfter(event.occurredAt) == true ||
            (existingVersion == event.occurredAt && existing.deleted)
        ) {
            return
        }
        val role = existing ?: TeamRoleProjection(
            roleId = roleId,
            teamId = event.teamId,
            sourceOccurredAt = event.occurredAt,
        )
        check(role.teamId == event.teamId) { "같은 roleId를 다른 teamId로 이동할 수 없습니다." }
        role.applyUpsert(requireNotNull(event.priority), event.occurredAt)
        roleRepository.save(role)
    }

    private fun deleteRole(event: PreferenceTeamRoleChangedEvent) {
        val roleId = requireNotNull(event.roleId)
        val existing = roleRepository.findById(roleId).orElse(null)
        if (existing?.sourceOccurredAt?.toProjectionPrecision()?.isAfter(event.occurredAt) == true) return
        val role = existing ?: TeamRoleProjection(
            roleId = roleId,
            teamId = event.teamId,
            deleted = true,
            sourceOccurredAt = event.occurredAt,
        )
        check(role.teamId == event.teamId) { "같은 roleId를 다른 teamId에서 삭제할 수 없습니다." }
        role.markDeleted(event.occurredAt)
        roleRepository.save(role)
        assignmentRepository.findAllByTeamIdAndRoleId(event.teamId, roleId)
            .filterNot { it.sourceOccurredAt.toProjectionPrecision().isAfter(event.occurredAt) }
            .forEach {
                it.markDeleted(event.occurredAt)
                assignmentRepository.save(it)
            }
    }

    private fun upsertAssignment(event: PreferenceTeamRoleChangedEvent) {
        val accountId = requireNotNull(event.accountId)
        val roleId = requireNotNull(event.roleId)
        val memberTombstone = memberTombstoneRepository.findById(
            TeamRoleMemberTombstone.key(event.teamId, accountId),
        ).orElse(null)
        if (memberTombstone != null &&
            !event.occurredAt.isAfter(memberTombstone.sourceOccurredAt.toProjectionPrecision())
        ) {
            return
        }

        val key = TeamRoleAssignmentProjection.key(event.teamId, accountId, roleId)
        val existing = assignmentRepository.findById(key).orElse(null)
        val existingVersion = existing?.sourceOccurredAt?.toProjectionPrecision()
        if (existingVersion?.isAfter(event.occurredAt) == true ||
            (existingVersion == event.occurredAt && existing.deleted)
        ) {
            return
        }
        val assignment = existing ?: TeamRoleAssignmentProjection(
            projectionKey = key,
            teamId = event.teamId,
            accountId = accountId,
            roleId = roleId,
            sourceOccurredAt = event.occurredAt,
        )
        assignment.markPresent(event.occurredAt)
        assignmentRepository.save(assignment)
    }

    private fun deleteAssignment(event: PreferenceTeamRoleChangedEvent) {
        val accountId = requireNotNull(event.accountId)
        val roleId = requireNotNull(event.roleId)
        val key = TeamRoleAssignmentProjection.key(event.teamId, accountId, roleId)
        val existing = assignmentRepository.findById(key).orElse(null)
        if (existing?.sourceOccurredAt?.toProjectionPrecision()?.isAfter(event.occurredAt) == true) return
        val assignment = existing ?: TeamRoleAssignmentProjection(
            projectionKey = key,
            teamId = event.teamId,
            accountId = accountId,
            roleId = roleId,
            deleted = true,
            sourceOccurredAt = event.occurredAt,
        )
        assignment.markDeleted(event.occurredAt)
        assignmentRepository.save(assignment)
    }

    private fun deleteMemberAssignments(event: PreferenceTeamRoleChangedEvent) {
        val accountId = requireNotNull(event.accountId)
        val key = TeamRoleMemberTombstone.key(event.teamId, accountId)
        val existing = memberTombstoneRepository.findById(key).orElse(null)
        if (existing?.sourceOccurredAt?.toProjectionPrecision()?.isAfter(event.occurredAt) == true) return
        val tombstone = existing ?: TeamRoleMemberTombstone(
            projectionKey = key,
            teamId = event.teamId,
            accountId = accountId,
            sourceOccurredAt = event.occurredAt,
        )
        tombstone.sourceOccurredAt = event.occurredAt
        memberTombstoneRepository.save(tombstone)
        assignmentRepository.findAllByTeamIdAndAccountId(event.teamId, accountId)
            .filterNot { it.sourceOccurredAt.toProjectionPrecision().isAfter(event.occurredAt) }
            .forEach {
                it.markDeleted(event.occurredAt)
                assignmentRepository.save(it)
            }
    }
}
