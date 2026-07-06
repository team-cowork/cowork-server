package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamInvite.entity.TeamInvite
import com.cowork.team.domain.teamInvite.presentation.data.request.CreateInviteRequest
import com.cowork.team.domain.teamInvite.presentation.data.request.InviteDuration
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
import java.util.Optional

class CreateInviteServiceImplTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamInviteRepository = mockk<TeamInviteRepository>()
    private val teamInviteAccessGuard = TeamInviteAccessGuard(teamRepository, teamMemberRepository)

    private val service = CreateInviteServiceImpl(teamInviteRepository, teamInviteAccessGuard)

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
    fun `createInvite는 멤버가 7d 초대 링크를 생성하면 InviteResponse를 반환`() {
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 42L) } returns normalMember
        every { teamRepository.findById(1L) } returns Optional.of(team)
        every { teamInviteRepository.existsByInviteCode(any()) } returns false
        val saved = makeInvite()
        every { teamInviteRepository.save(any()) } returns saved

        val result = service.execute(42L, 1L, CreateInviteRequest(InviteDuration.SEVEN_DAYS))

        assertEquals("aB3xK9mZ", result.inviteCode)
        assertEquals("7d", result.duration)
        assertEquals(false, result.expired)
    }

    @Test
    fun `createInvite는 팀 멤버가 아니면 403`() {
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 99L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, CreateInviteRequest(InviteDuration.SEVEN_DAYS))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
