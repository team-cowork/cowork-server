package com.cowork.team

import com.cowork.global.storage.StorageService
import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamVisibility
import com.cowork.team.repository.TeamMemberCount
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.team.service.TeamService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TeamServiceMyTeamsTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val service = TeamService(
        teamRepository, teamMemberRepository, mockk(), mockk(), mockk(), mockk<StorageService>(), mockk(),
    )

    private fun team(id: Long) = Team(name = "team$id", description = null, visibility = TeamVisibility.PUBLIC, ownerId = 1, id = id)

    private fun count(teamId: Long, count: Long) = object : TeamMemberCount {
        override val teamId = teamId
        override val count = count
    }

    @Test
    fun `내 팀 목록은 역할과 멤버 수를 한 번에 조회한다`() {
        every { teamRepository.findAllByMemberUserId(5) } returns listOf(team(1), team(2))
        every { teamMemberRepository.findAllByUserId(5) } returns listOf(
            TeamMember(teamId = 1, userId = 5, role = TeamMemberRole.OWNER),
            TeamMember(teamId = 2, userId = 5, role = TeamMemberRole.MEMBER),
        )
        every { teamMemberRepository.countByTeamIds(listOf(1, 2)) } returns listOf(count(1, 3), count(2, 8))

        val teams = service.getMyTeams(5)

        assertEquals(listOf(TeamMemberRole.OWNER, TeamMemberRole.MEMBER), teams.map { it.myRole })
        assertEquals(listOf(3L, 8L), teams.map { it.memberCount })
        verify(exactly = 0) { teamMemberRepository.findByTeamIdAndUserId(any(), any()) }
    }

    @Test
    fun `속한 팀이 없으면 추가 조회를 하지 않는다`() {
        every { teamRepository.findAllByMemberUserId(5) } returns emptyList()

        assertEquals(emptyList<Any>(), service.getMyTeams(5))
        verify(exactly = 0) { teamMemberRepository.countByTeamIds(any()) }
    }
}
