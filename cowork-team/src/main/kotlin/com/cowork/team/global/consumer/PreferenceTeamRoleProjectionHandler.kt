package com.cowork.team.global.consumer

import com.cowork.team.domain.teamRole.projection.TeamRoleAssignmentProjection
import com.cowork.team.domain.teamRole.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.team.domain.teamRole.projection.TeamRoleMemberTombstone
import com.cowork.team.domain.teamRole.projection.TeamRoleMemberTombstoneRepository
import com.cowork.team.domain.teamRole.projection.TeamRoleProjection
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionRepository
import com.cowork.team.global.projection.toProjectionPrecision
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Component
class PreferenceTeamRoleProjectionHandler(
    private val roleRepository: TeamRoleProjectionRepository,
    private val assignmentRepository: TeamRoleAssignmentProjectionRepository,
    private val memberTombstoneRepository: TeamRoleMemberTombstoneRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(PreferenceTeamRoleProjectionHandler::class.java)

    @Transactional
    fun handle(sourceEvent: PreferenceTeamRoleChangedEvent) {
        val event = sourceEvent.copy(occurredAt = sourceEvent.occurredAt.toProjectionPrecision())
        when (event.eventType) {
            "ROLE_UPSERTED" -> upsertRole(event)
            "ROLE_DELETED" -> deleteRole(event)
            "ASSIGNMENT_UPSERTED" -> upsertAssignment(event)
            "ASSIGNMENT_DELETED" -> deleteAssignment(event)
            "MEMBER_ASSIGNMENTS_DELETED" -> deleteMemberAssignments(event)
            else -> log.warn("알 수 없는 preference team-role 이벤트를 무시합니다 [eventType={}]", event.eventType)
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
            sourceCreatedAt = event.occurredAt,
            sourceOccurredAt = event.occurredAt,
        )
        role.applyUpsert(
            name = requireNotNull(event.name),
            colorHex = requireNotNull(event.colorHex),
            priority = requireNotNull(event.priority),
            mentionable = requireNotNull(event.mentionable),
            permissionsJson = objectMapper.writeValueAsString(event.permissions.orEmpty()),
            occurredAt = event.occurredAt,
        )
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
            sourceCreatedAt = event.occurredAt,
            sourceOccurredAt = event.occurredAt,
        )
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
        val existingTombstone = memberTombstoneRepository.findById(key).orElse(null)
        if (existingTombstone?.sourceOccurredAt?.toProjectionPrecision()?.isAfter(event.occurredAt) == true) return
        val tombstone = existingTombstone ?: TeamRoleMemberTombstone(
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
