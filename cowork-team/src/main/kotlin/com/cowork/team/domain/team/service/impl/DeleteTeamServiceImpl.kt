package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.DeleteTeamService
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class DeleteTeamServiceImpl(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamAccessGuard: TeamAccessGuard,
) : DeleteTeamService {

    @Transactional
    override fun execute(userId: Long, teamId: Long) {
        val team = teamRepository.findByIdForUpdate(teamId)
            ?: throw ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER)
        val occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val members = teamMemberRepository.findAllByTeamIdForUpdate(teamId)
        members.forEach { teamMemberEventPublisher.publishDelete(it, occurredAt) }
        teamEventPublisher.publishDeleted(team, userId, occurredAt)
        teamMemberRepository.deleteAll(members)
        teamRepository.delete(team)
    }
}
