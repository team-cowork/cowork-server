package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.teamInvite.entity.TeamInvite
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

class JoinTeamServiceImplTest {

    private val teamInviteRepository = mockk<TeamInviteRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamEventPublisher = mockk<TeamEventPublisher>(relaxed = true)

    private val service = JoinTeamServiceImpl(teamInviteRepository, teamMemberRepository, teamEventPublisher)

    private val team = Team(id = 1L, name = "테스트팀", description = null, iconUrl = null, ownerId = 10L)

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    private fun fireAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
    }

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
    fun `joinTeam은 유효한 코드로 가입 성공 시 MEMBER_JOINED 이벤트를 발행`() {
        val invite = makeInvite(createdBy = 10L)
        every { teamInviteRepository.findActiveByInviteCode("aB3xK9mZ") } returns invite
        every { teamMemberRepository.existsByTeamIdAndUserId(1L, 99L) } returns false
        val newMember = TeamMember(team = team, userId = 99L).also {
            val f = TeamMember::class.java.getDeclaredField("joinedAt").also { f -> f.isAccessible = true }
            f.set(it, LocalDateTime.now())
        }
        every { teamMemberRepository.save(any()) } returns newMember

        val captured = slot<TeamEventPayload>()
        every { teamEventPublisher.publishLifecycle(capture(captured)) } just Runs

        val result = service.joinTeam(99L, "aB3xK9mZ")
        fireAfterCommit()

        assertEquals(1L, result.teamId)
        assertEquals(99L, result.userId)
        assertEquals("MEMBER", result.role)
        verify(exactly = 1) { teamEventPublisher.publishLifecycle(any()) }
        assertEquals("MEMBER_JOINED", captured.captured.eventType)
        assertEquals(listOf(99L), captured.captured.targetUserIds)
    }

    @Test
    fun `joinTeam은 만료된 코드면 410`() {
        val expired = makeInvite(expiresAt = LocalDateTime.now().minusHours(1))
        every { teamInviteRepository.findActiveByInviteCode("aB3xK9mZ") } returns expired

        val ex = assertThrows(ExpectedException::class.java) {
            service.joinTeam(99L, "aB3xK9mZ")
        }
        assertEquals(HttpStatus.GONE, ex.statusCode)
    }

    @Test
    fun `joinTeam은 이미 팀 멤버면 409`() {
        val invite = makeInvite()
        every { teamInviteRepository.findActiveByInviteCode("aB3xK9mZ") } returns invite
        every { teamMemberRepository.existsByTeamIdAndUserId(1L, 42L) } returns true

        val ex = assertThrows(ExpectedException::class.java) {
            service.joinTeam(42L, "aB3xK9mZ")
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `joinTeam은 존재하지 않거나 삭제된 코드면 404`() {
        every { teamInviteRepository.findActiveByInviteCode("invalid") } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.joinTeam(99L, "invalid")
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
