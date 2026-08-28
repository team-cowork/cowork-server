package com.cowork.team.domain.team.event

import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamMemberEventState
import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.global.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Transactional(propagation = Propagation.MANDATORY)
class TeamMemberEventPublisher(
    private val teamRepository: TeamRepository,
    private val memberRepository: TeamMemberRepository,
    private val stateRepository: TeamMemberEventStateRepository,
    private val entityManager: EntityManager,
    private val outboxWriter: OutboxWriter,
) {
    fun publishUpsert(member: TeamMember, occurredAt: Instant = Instant.now()) =
        publishMutation(member, deleted = false, occurredAt = occurredAt)

    fun publishDelete(member: TeamMember, occurredAt: Instant = Instant.now()) =
        publishMutation(member, deleted = true, occurredAt = occurredAt)

    fun publishSnapshot(state: TeamMemberEventState) {
        entityManager.flush()
        enqueue(state, snapshot = true)
    }

    private fun publishMutation(member: TeamMember, deleted: Boolean, occurredAt: Instant): Instant {
        val team = checkNotNull(teamRepository.findByIdForUpdate(member.team.id)) {
            "Team row must exist while publishing its member state mutation: ${member.team.id}"
        }
        val lockedMember = checkNotNull(memberRepository.findByTeamIdAndUserIdForUpdate(team.id, member.userId)) {
            "Team member row must exist while publishing its state mutation: ${team.id}:${member.userId}"
        }
        val existingState = stateRepository.findByKeyForUpdate(team.id, lockedMember.userId)
        val state = existingState ?: TeamMemberEventState.create(team, lockedMember, deleted, occurredAt)
        val version = if (existingState == null) {
            state.stateOccurredAt
        } else {
            state.apply(team, lockedMember, deleted, occurredAt)
        }
        stateRepository.save(state)
        entityManager.flush()
        enqueue(state, snapshot = false)
        return version
    }

    private fun enqueue(state: TeamMemberEventState, snapshot: Boolean) {
        val event = TeamMemberEvent(
            eventType = if (state.deleted) "DELETE" else "UPSERT",
            teamId = state.teamId,
            userId = state.userId,
            role = state.role.name,
            teamName = state.teamName,
            occurredAt = state.stateOccurredAt,
            snapshot = snapshot,
        )
        val key = "${event.teamId}:${event.userId}"
        outboxWriter.enqueue(Topics.TEAM_MEMBER_EVENT, key, event)
    }
}
