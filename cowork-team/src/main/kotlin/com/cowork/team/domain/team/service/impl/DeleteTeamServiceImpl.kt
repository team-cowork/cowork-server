package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.calendar.TeamCalendarCleanup
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.DeleteTeamService
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TeamIconOwnershipManager
import com.cowork.team.domain.teamMember.TeamMemberships
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class DeleteTeamServiceImpl(
    private val teamRepository: TeamRepository,
    private val teamMemberships: TeamMemberships,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamAccessGuard: TeamAccessGuard,
    private val teamIconOwnershipManager: TeamIconOwnershipManager,
    private val teamCalendarCleanup: TeamCalendarCleanup,
) : DeleteTeamService {

    @Transactional
    override fun execute(userId: Long, teamId: Long) {
        val team = teamRepository.findByIdForUpdate(teamId)
            ?: throw ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        teamAccessGuard.requirePermission(teamId, userId, "delete_team")
        val occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        teamMemberships.removeAll(teamId, occurredAt)
        teamEventPublisher.publishDeleted(team, userId, occurredAt)
        teamCalendarCleanup.removeAll(teamId)
        teamIconOwnershipManager.clear(team)
        teamRepository.delete(team)
    }
}
