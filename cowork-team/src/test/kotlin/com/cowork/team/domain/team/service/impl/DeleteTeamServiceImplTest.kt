package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class DeleteTeamServiceImplTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamEventPublisher = mockk<TeamEventPublisher>(relaxed = true)
    private val teamMemberEventPublisher = mockk<TeamMemberEventPublisher>(relaxed = true)
    private val teamAccessGuard = TeamAccessGuard(teamRepository, teamMemberRepository)

    private val service =
        DeleteTeamServiceImpl(
            teamRepository,
            teamMemberRepository,
            teamEventPublisher,
            teamMemberEventPublisher,
            teamAccessGuard,
        )

    @Test
    fun `deleteTeam은 OWNER 권한 통과 시 TEAM_DELETED 이벤트를 lifecycle 토픽으로 발행`() {
        val teamId = 10L
        val ownerId = 1L
        val team = Team(id = teamId, name = "팀A", description = null, iconUrl = null, ownerId = ownerId)

        val owner = TeamMember(team = team, userId = ownerId, role = TeamRole.OWNER)
        val member = TeamMember(team = team, userId = 2L, role = TeamRole.MEMBER)
        every { teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, ownerId, listOf(TeamRole.OWNER)) } returns
            owner
        every { teamMemberRepository.findAllByTeamId(teamId) } returns listOf(owner, member)
        every { teamRepository.findById(teamId) } returns Optional.of(team)
        every { teamRepository.delete(team) } just Runs

        val captured = slot<TeamEventPayload>()
        every { teamEventPublisher.publishLifecycle(capture(captured)) } just Runs

        service.execute(ownerId, teamId)

        verify(exactly = 1) { teamEventPublisher.publishLifecycle(any()) }
        val memberDeleteVersions = mutableListOf<Instant>()
        verify(exactly = 2) { teamMemberEventPublisher.publishDelete(any(), capture(memberDeleteVersions), false) }
        assertEquals("TEAM_DELETED", captured.captured.eventType)
        assertEquals(teamId, captured.captured.teamId)
        assertEquals("팀A", captured.captured.teamName)
        assertEquals(ownerId, captured.captured.actorUserId)
        assertEquals(listOf(captured.captured.occurredAt, captured.captured.occurredAt), memberDeleteVersions)
        assertEquals(0, captured.captured.occurredAt.nano % 1_000)
    }
}
