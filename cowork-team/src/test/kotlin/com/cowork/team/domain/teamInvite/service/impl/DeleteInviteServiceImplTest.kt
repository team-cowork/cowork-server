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

class DeleteInviteServiceImplTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamInviteRepository = mockk<TeamInviteRepository>()
    private val teamInviteAccessGuard = TeamInviteAccessGuard(teamRepository, teamMemberRepository)

    private val service = DeleteInviteServiceImpl(teamInviteRepository, teamInviteAccessGuard)

    private val team = Team(id = 1L, name = "테스트팀", description = null, iconUrl = null, ownerId = 10L)
    private val ownerMember = TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)
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
    fun `deleteInvite는 생성자 본인이면 soft delete 성공`() {
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 42L) } returns normalMember
        val invite = makeInvite(createdBy = 42L)
        every { teamInviteRepository.findByTeamIdAndInviteCode(1L, "aB3xK9mZ") } returns invite

        service.deleteInvite(42L, 1L, "aB3xK9mZ")

        assertEquals(true, invite.isDeleted())
    }

    @Test
    fun `deleteInvite는 OWNER가 타인 링크를 삭제할 수 있다`() {
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 10L) } returns ownerMember
        val invite = makeInvite(createdBy = 42L)
        every { teamInviteRepository.findByTeamIdAndInviteCode(1L, "aB3xK9mZ") } returns invite

        service.deleteInvite(10L, 1L, "aB3xK9mZ")

        assertEquals(true, invite.isDeleted())
    }

    @Test
    fun `deleteInvite는 생성자도 OWNER_ADMIN도 아니면 403`() {
        val otherMember = TeamMember(team = team, userId = 55L, role = TeamRole.MEMBER)
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 55L) } returns otherMember
        val invite = makeInvite(createdBy = 42L)
        every { teamInviteRepository.findByTeamIdAndInviteCode(1L, "aB3xK9mZ") } returns invite

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteInvite(55L, 1L, "aB3xK9mZ")
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `deleteInvite는 존재하지 않는 코드면 404`() {
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 42L) } returns normalMember
        every { teamInviteRepository.findByTeamIdAndInviteCode(1L, "notfound") } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteInvite(42L, 1L, "notfound")
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
