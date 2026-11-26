package com.cowork.team.domain.teamMember.event

import com.cowork.team.domain.teamAccess.TeamDirectory
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamMemberEventState
import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.global.outbox.OutboxWriter
import com.cowork.team.global.outbox.Topics
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class TeamMemberEventPublisher(
    private val teamDirectory: TeamDirectory,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamMemberEventStateRepository: TeamMemberEventStateRepository,
    private val entityManager: EntityManager,
    private val outboxWriter: OutboxWriter,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun publishUpsert(member: TeamMember, occurredAt: Instant = Instant.now()) =
        publishMutation(member, deleted = false, occurredAt = occurredAt)

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishDelete(member: TeamMember, occurredAt: Instant = Instant.now()) =
        publishMutation(member, deleted = true, occurredAt = occurredAt)

    @Transactional(propagation = Propagation.MANDATORY)
    fun publishSnapshot(state: TeamMemberEventState, joinedAt: LocalDateTime?) {
        entityManager.flush()
        enqueue(state, joinedAt, snapshot = true)
    }

    private fun publishMutation(member: TeamMember, deleted: Boolean, occurredAt: Instant): Instant {
        val team = checkNotNull(teamDirectory.lock(member.teamId)) {
            "Team row must exist while publishing its member state mutation: ${member.teamId}"
        }
        val lockedMember = checkNotNull(teamMemberRepository.findByTeamIdAndUserIdForUpdate(team.id, member.userId)) {
            "Team member row must exist while publishing its state mutation: ${team.id}:${member.userId}"
        }
        val existingState = teamMemberEventStateRepository.findByKeyForUpdate(team.id, lockedMember.userId)
        val state = existingState ?: TeamMemberEventState.create(team, lockedMember, deleted, occurredAt)
        val version = if (existingState == null) {
            state.stateOccurredAt
        } else {
            state.apply(team, lockedMember, deleted, occurredAt)
        }
        teamMemberEventStateRepository.save(state)
        entityManager.flush()
        enqueue(state, lockedMember.joinedAt, snapshot = false)
        return version
    }

    private fun enqueue(state: TeamMemberEventState, joinedAt: LocalDateTime?, snapshot: Boolean) {
        val event = TeamMemberEvent(
            eventType = if (state.deleted) "DELETE" else "UPSERT",
            teamId = state.teamId,
            userId = state.userId,
            role = state.role.name,
            teamName = state.teamName,
            nickname = state.nickname,
            occurredAt = state.stateOccurredAt,
            authorizationVersion = state.authorizationVersion,
            // JPA auditing records joinedAt in the JVM local time zone.
            joinedAt = if (state.deleted) null else requireNotNull(joinedAt).atZone(ZoneId.systemDefault()).toInstant(),
            snapshot = snapshot,
        )
        val key = "${event.teamId}:${event.userId}"
        outboxWriter.enqueue(Topics.TEAM_MEMBER_EVENT, key, event)
    }
}
