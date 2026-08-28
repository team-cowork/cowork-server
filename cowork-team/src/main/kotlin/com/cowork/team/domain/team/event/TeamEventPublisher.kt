package com.cowork.team.domain.team.event

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.entity.TeamEventState
import com.cowork.team.domain.team.repository.TeamEventStateRepository
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.global.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Transactional(propagation = Propagation.MANDATORY)
class TeamEventPublisher(
    private val teamRepository: TeamRepository,
    private val stateRepository: TeamEventStateRepository,
    private val entityManager: EntityManager,
    private val outboxWriter: OutboxWriter,
) {
    fun publishNotification(teamId: Long, event: NotificationTriggerEvent) {
        entityManager.flush()
        outboxWriter.enqueue(Topics.NOTIFICATION_TRIGGER, teamId.toString(), event)
    }

    fun publishCreated(team: Team, actorUserId: Long, requestedAt: Instant = Instant.now()): Instant =
        publishMutation("TEAM_CREATED", team, actorUserId, deleted = false, requestedAt = requestedAt)

    fun publishUpdated(team: Team, actorUserId: Long, requestedAt: Instant = Instant.now()): Instant =
        publishMutation("TEAM_UPDATED", team, actorUserId, deleted = false, requestedAt = requestedAt)

    fun publishDeleted(team: Team, actorUserId: Long, requestedAt: Instant = Instant.now()): Instant =
        publishMutation("TEAM_DELETED", team, actorUserId, deleted = true, requestedAt = requestedAt)

    fun publishSnapshot(state: TeamEventState) {
        entityManager.flush()
        enqueue(
            eventType = if (state.deleted) "TEAM_DELETED" else "TEAM_UPDATED",
            state = state,
            snapshot = true,
        )
    }

    private fun publishMutation(
        eventType: String,
        team: Team,
        actorUserId: Long,
        deleted: Boolean,
        requestedAt: Instant,
    ): Instant {
        val lockedTeam = checkNotNull(teamRepository.findByIdForUpdate(team.id)) {
            "Team row must exist while publishing its state mutation: ${team.id}"
        }
        val existingState = stateRepository.findByTeamIdForUpdate(lockedTeam.id)
        val state = existingState ?: TeamEventState.create(lockedTeam, actorUserId, deleted, requestedAt)
        val version = if (existingState == null) {
            state.stateOccurredAt
        } else {
            state.apply(lockedTeam, actorUserId, deleted, requestedAt)
        }
        stateRepository.save(state)
        entityManager.flush()
        check((eventType == "TEAM_DELETED") == state.deleted) {
            "Team lifecycle event type must match its persisted deletion state"
        }
        enqueue(eventType, state, snapshot = false)
        return version
    }

    private fun enqueue(eventType: String, state: TeamEventState, snapshot: Boolean) {
        val payload = TeamEventPayload(
            eventType = eventType,
            teamId = state.teamId,
            teamName = state.name,
            actorUserId = state.actorUserId,
            occurredAt = state.stateOccurredAt,
            snapshot = snapshot,
            description = state.description,
            iconUrl = state.iconUrl,
            ownerUserId = state.ownerId,
            githubInstallationId = state.githubInstallationId,
            githubOrgLogin = state.githubOrgLogin,
        )
        outboxWriter.enqueue(Topics.TEAM_LIFECYCLE, state.teamId.toString(), payload)
    }
}
