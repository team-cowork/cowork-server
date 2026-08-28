package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
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
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

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
    fun `deleteTeam은 같은 requested version으로 member와 team tombstone을 기록한 뒤 원본을 삭제한다`() {
        val teamId = 10L
        val ownerId = 1L
        val team = Team(id = teamId, name = "팀A", description = null, iconUrl = null, ownerId = ownerId)

        val owner = TeamMember(team = team, userId = ownerId, role = TeamRole.OWNER)
        val member = TeamMember(team = team, userId = 2L, role = TeamRole.MEMBER)
        every { teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, ownerId, listOf(TeamRole.OWNER)) } returns
            owner
        every { teamMemberRepository.findAllByTeamIdForUpdate(teamId) } returns listOf(owner, member)
        every { teamRepository.findByIdForUpdate(teamId) } returns team
        every { teamMemberRepository.deleteAll(listOf(owner, member)) } just Runs
        every { teamRepository.delete(team) } just Runs

        val memberDeleteVersions = mutableListOf<Instant>()
        val teamDeleteVersions = mutableListOf<Instant>()
        every { teamMemberEventPublisher.publishDelete(any(), capture(memberDeleteVersions)) } answers {
            secondArg()
        }
        every { teamEventPublisher.publishDeleted(team, ownerId, capture(teamDeleteVersions)) } answers {
            thirdArg()
        }

        service.execute(ownerId, teamId)

        verify(exactly = 1) { teamEventPublisher.publishDeleted(team, ownerId, any()) }
        verify(exactly = 2) { teamMemberEventPublisher.publishDelete(any(), any()) }
        assertEquals(listOf(teamDeleteVersions.single(), teamDeleteVersions.single()), memberDeleteVersions)
        assertEquals(0, teamDeleteVersions.single().nano % 1_000)
        verify(exactly = 1) { teamMemberRepository.deleteAll(listOf(owner, member)) }
        verify(exactly = 1) { teamRepository.delete(team) }
        verifyOrder {
            teamMemberEventPublisher.publishDelete(owner, any())
            teamMemberEventPublisher.publishDelete(member, any())
            teamEventPublisher.publishDeleted(team, ownerId, any())
            teamMemberRepository.deleteAll(listOf(owner, member))
            teamRepository.delete(team)
        }
    }
}
