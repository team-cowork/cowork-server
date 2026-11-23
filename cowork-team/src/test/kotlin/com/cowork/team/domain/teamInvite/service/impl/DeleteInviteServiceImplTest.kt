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

class DeleteInviteServiceImplTest {

    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamInviteRepository = mockk<TeamInviteRepository>()
    private val teamPermissionGuard = TeamPermissionGuard(
        teamMemberRepository,
        policyReader(
            1,
            mapOf(
                10L to mapOf("manage_invites" to true),
                42L to mapOf("manage_invites" to true),
            ),
        ),
    )

    private val service = DeleteInviteServiceImpl(teamInviteRepository, teamPermissionGuard)

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
    fun `deleteInvite는 관리 권한이 있으면 soft delete 성공`() {
        every { teamMemberRepository.existsByTeamIdAndUserId(1L, 42L) } returns true
        val invite = makeInvite(createdBy = 42L)
        every { teamInviteRepository.findByTeamIdAndInviteCode(1L, "aB3xK9mZ") } returns invite

        service.execute(42L, 1L, "aB3xK9mZ")

        assertEquals(true, invite.isDeleted())
    }

    @Test
    fun `deleteInvite는 OWNER가 타인 링크를 삭제할 수 있다`() {
        every { teamMemberRepository.existsByTeamIdAndUserId(1L, 10L) } returns true
        val invite = makeInvite(createdBy = 42L)
        every { teamInviteRepository.findByTeamIdAndInviteCode(1L, "aB3xK9mZ") } returns invite

        service.execute(10L, 1L, "aB3xK9mZ")

        assertEquals(true, invite.isDeleted())
    }

    @Test
    fun `deleteInvite는 생성자도 OWNER_ADMIN도 아니면 403`() {
        every { teamMemberRepository.existsByTeamIdAndUserId(1L, 55L) } returns true
        val invite = makeInvite(createdBy = 42L)
        every { teamInviteRepository.findByTeamIdAndInviteCode(1L, "aB3xK9mZ") } returns invite

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(55L, 1L, "aB3xK9mZ")
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `deleteInvite는 존재하지 않는 코드면 404`() {
        every { teamMemberRepository.existsByTeamIdAndUserId(1L, 42L) } returns true
        every { teamInviteRepository.findByTeamIdAndInviteCode(1L, "notfound") } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(42L, 1L, "notfound")
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
