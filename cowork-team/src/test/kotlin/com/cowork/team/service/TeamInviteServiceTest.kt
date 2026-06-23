package com.cowork.team.service

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamInvite.entity.TeamInvite
import com.cowork.team.domain.teamInvite.presentation.data.request.CreateInviteRequest
import com.cowork.team.domain.teamInvite.presentation.data.request.InviteDuration
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamInvite.service.TeamInviteService
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
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
import java.util.Optional

class TeamInviteServiceTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamInviteRepository = mockk<TeamInviteRepository>()
    private val teamEventPublisher = mockk<TeamEventPublisher>(relaxed = true)

    private val service = TeamInviteService(
        teamRepository = teamRepository,
        teamMemberRepository = teamMemberRepository,
        teamInviteRepository = teamInviteRepository,
        teamEventPublisher = teamEventPublisher,
    )

    private val team = Team(id = 1L, name = "테스트팀", description = null, iconUrl = null, ownerId = 10L)
    private val ownerMember = TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)
    private val normalMember = TeamMember(team = team, userId = 42L, role = TeamRole.MEMBER)

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
        deletedAt: LocalDateTime? = null,
    ) = TeamInvite(
        team = team,
        inviteCode = inviteCode,
        createdBy = createdBy,
        duration = duration,
        expiresAt = expiresAt,
    ).also {
        it.deletedAt = deletedAt
        // createdAt은 JPA auditing이 채우지만 테스트에서는 직접 설정
        val field = TeamInvite::class.java.getDeclaredField("createdAt").also { f -> f.isAccessible = true }
        field.set(it, LocalDateTime.now())
    }

    // ──────────────────────────────────────────────
    // createInvite
    // ──────────────────────────────────────────────

    @Test
    fun `createInvite는 멤버가 7d 초대 링크를 생성하면 InviteResponse를 반환`() {
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 42L) } returns normalMember
        every { teamRepository.findById(1L) } returns Optional.of(team)
        every { teamInviteRepository.existsByInviteCode(any()) } returns false
        val saved = makeInvite()
        every { teamInviteRepository.save(any()) } returns saved

        val result = service.createInvite(42L, 1L, CreateInviteRequest(InviteDuration.SEVEN_DAYS))

        assertEquals("aB3xK9mZ", result.inviteCode)
        assertEquals("7d", result.duration)
        assertEquals(false, result.expired)
    }

    @Test
    fun `createInvite는 팀 멤버가 아니면 403`() {
        every { teamMemberRepository.findByTeamIdAndUserId(1L, 99L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.createInvite(99L, 1L, CreateInviteRequest(InviteDuration.SEVEN_DAYS))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    // ──────────────────────────────────────────────
    // getInvites
    // ──────────────────────────────────────────────

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

    // ──────────────────────────────────────────────
    // deleteInvite
    // ──────────────────────────────────────────────

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

    // ──────────────────────────────────────────────
    // joinTeam
    // ──────────────────────────────────────────────

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
