package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamInvite.entity.TeamInvite
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamInvite.service.TeamInviteAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class GetInvitesServiceImplTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamInviteRepository = mockk<TeamInviteRepository>()
    private val teamInviteAccessGuard = TeamInviteAccessGuard(teamRepository, teamMemberRepository)

    private val service = GetInvitesServiceImpl(teamInviteRepository, teamInviteAccessGuard)

    private val team = Team(id = 1L, name = "테스트팀", description = null, iconUrl = null, ownerId = 10L)
    private val normalMember = TeamMember(team = team, userId = 42L, role = TeamRole.MEMBER)

    private fun makeInvite(
        inviteCode: String = "aB3xK9mZ",
        createdBy: Long = 42L,
        duration: String = "7d",
        expiresAt: LocalDateTime? = LocalDateTime.now().plusDays(7),
    ) = TeamInvite(
        team = team,
        inviteCode = inviteCode,
        createdBy = createdBy,
        duration = duration,
        expiresAt = expiresAt,
    ).also {
        val field = TeamInvite::class.java.getDeclaredField("createdAt").also { f -> f.isAccessible = true }
        field.set(it, LocalDateTime.now())
    }

    @Test
    fun `getInvites는 만료된 링크 포함 전체 목록을 반환`() {
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 42L) } returns normalMember
        val active = makeInvite(inviteCode = "active01")
        val expired = makeInvite(inviteCode = "expird01", expiresAt = LocalDateTime.now().minusDays(1))
        every { teamInviteRepository.findAllByTeamId(1L) } returns listOf(active, expired)

        val result = service.getInvites(42L, 1L)

        assertEquals(2, result.size)
        assertEquals(false, result.first { it.inviteCode == "active01" }.expired)
        assertEquals(true, result.first { it.inviteCode == "expird01" }.expired)
    }

    @Test
    fun `getInvites는 팀 멤버가 아니면 403`() {
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 99L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.getInvites(99L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
