package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.teamInvite.entity.TeamInvite
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamMember.TeamPermissionGuard
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.support.policyReader
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class QueryInvitesServiceImplTest {

    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamInviteRepository = mockk<TeamInviteRepository>()
    private val teamPermissionGuard =
        TeamPermissionGuard(
            teamMemberRepository,
            policyReader(1, mapOf(42L to mapOf("manage_invites" to true))),
            mockk(),
        )

    private val service = QueryInvitesServiceImpl(teamInviteRepository, teamPermissionGuard)

    private fun makeInvite(
        inviteCode: String = "aB3xK9mZ",
        createdBy: Long = 42L,
        duration: String = "7d",
        expiresAt: LocalDateTime? = LocalDateTime.now().plusDays(7),
    ) = TeamInvite(
        teamId = 1L,
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
        every { teamMemberRepository.existsByTeamIdAndUserId(1L, 42L) } returns true
        val active = makeInvite(inviteCode = "active01")
        val expired = makeInvite(inviteCode = "expird01", expiresAt = LocalDateTime.now().minusDays(1))
        every { teamInviteRepository.findAllByTeamId(1L) } returns listOf(active, expired)

        val result = service.execute(42L, 1L)

        assertEquals(2, result.size)
        assertEquals(false, result.first { it.inviteCode == "active01" }.expired)
        assertEquals(true, result.first { it.inviteCode == "expird01" }.expired)
    }

    @Test
    fun `getInvites는 팀 멤버가 아니면 403`() {
        every { teamMemberRepository.existsByTeamIdAndUserId(1L, 99L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
