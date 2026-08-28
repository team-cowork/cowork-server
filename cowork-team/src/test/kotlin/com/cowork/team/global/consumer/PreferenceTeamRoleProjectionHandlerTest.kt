package com.cowork.team.global.consumer

import com.cowork.team.domain.teamRole.projection.TeamRoleAssignmentProjection
import com.cowork.team.domain.teamRole.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.team.domain.teamRole.projection.TeamRoleMemberTombstone
import com.cowork.team.domain.teamRole.projection.TeamRoleMemberTombstoneRepository
import com.cowork.team.domain.teamRole.projection.TeamRoleProjection
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Optional

class PreferenceTeamRoleProjectionHandlerTest {
    private val roleRepository = mockk<TeamRoleProjectionRepository>()
    private val assignmentRepository = mockk<TeamRoleAssignmentProjectionRepository>(relaxed = true)
    private val tombstoneRepository = mockk<TeamRoleMemberTombstoneRepository>(relaxed = true)
    private val handler = PreferenceTeamRoleProjectionHandler(
        roleRepository,
        assignmentRepository,
        tombstoneRepository,
        jacksonObjectMapper(),
    )

    @Test
    fun `ROLE_UPSERTED는 역할 projection의 전체 상태를 저장`() {
        val saved = slot<TeamRoleProjection>()
        every { roleRepository.findById(9L) } returns Optional.empty()
        every { roleRepository.save(capture(saved)) } answers { firstArg() }

        handler.handle(
            PreferenceTeamRoleChangedEvent(
                eventType = "ROLE_UPSERTED",
                teamId = 3L,
                roleId = 9L,
                name = "Reviewer",
                colorHex = "#5865F2",
                priority = 10,
                mentionable = true,
                permissions = setOf("MANAGE_ROLES"),
                occurredAt = Instant.parse("2026-08-26T12:00:00.123456999Z"),
            ),
        )

        assertEquals("Reviewer", saved.captured.name)
        assertEquals(10, saved.captured.priority)
        assertEquals("[\"MANAGE_ROLES\"]", saved.captured.permissionsJson)
        assertEquals(Instant.parse("2026-08-26T12:00:00.123456Z"), saved.captured.sourceOccurredAt)
    }

    @Test
    fun `더 오래된 ROLE_UPSERTED는 최신 projection을 덮어쓰지 않음`() {
        val existing = TeamRoleProjection(
            roleId = 9L,
            teamId = 3L,
            name = "Latest",
            sourceCreatedAt = Instant.parse("2026-08-26T11:00:00Z"),
            sourceOccurredAt = Instant.parse("2026-08-26T13:00:00Z"),
        )
        every { roleRepository.findById(9L) } returns Optional.of(existing)

        handler.handle(
            PreferenceTeamRoleChangedEvent(
                eventType = "ROLE_UPSERTED",
                teamId = 3L,
                roleId = 9L,
                name = "Stale",
                colorHex = "#000000",
                priority = 1,
                mentionable = false,
                occurredAt = Instant.parse("2026-08-26T12:00:00Z"),
            ),
        )

        verify(exactly = 0) { roleRepository.save(any()) }
        assertEquals("Latest", existing.name)
    }

    @Test
    fun `같은 DB microsecond의 ROLE_UPSERTED는 삭제된 role을 되살리지 않음`() {
        val deletedAt = Instant.parse("2026-08-26T13:00:00.123456Z")
        val existing = TeamRoleProjection(
            roleId = 9L,
            teamId = 3L,
            name = "Deleted",
            deleted = true,
            sourceCreatedAt = deletedAt.minusSeconds(10),
            sourceOccurredAt = deletedAt,
        )
        every { roleRepository.findById(9L) } returns Optional.of(existing)

        handler.handle(
            PreferenceTeamRoleChangedEvent(
                eventType = "ROLE_UPSERTED",
                teamId = 3L,
                roleId = 9L,
                name = "Resurrected",
                colorHex = "#000000",
                priority = 1,
                mentionable = false,
                occurredAt = Instant.parse("2026-08-26T13:00:00.123456999Z"),
            ),
        )

        verify(exactly = 0) { roleRepository.save(any()) }
        assertEquals(true, existing.deleted)
        assertEquals("Deleted", existing.name)
    }

    @Test
    fun `같은 DB microsecond의 ASSIGNMENT_UPSERTED는 개별 삭제 tombstone을 되살리지 않음`() {
        val deletedAt = Instant.parse("2026-08-26T13:00:00.123456Z")
        val key = TeamRoleAssignmentProjection.key(3L, 7L, 9L)
        val existing = TeamRoleAssignmentProjection(
            projectionKey = key,
            teamId = 3L,
            accountId = 7L,
            roleId = 9L,
            deleted = true,
            sourceOccurredAt = deletedAt,
        )
        every { tombstoneRepository.findById(TeamRoleMemberTombstone.key(3L, 7L)) } returns Optional.empty()
        every { assignmentRepository.findById(key) } returns Optional.of(existing)

        handler.handle(
            PreferenceTeamRoleChangedEvent(
                eventType = "ASSIGNMENT_UPSERTED",
                teamId = 3L,
                accountId = 7L,
                roleId = 9L,
                occurredAt = Instant.parse("2026-08-26T13:00:00.123456999Z"),
            ),
        )

        verify(exactly = 0) { assignmentRepository.save(any()) }
        assertEquals(true, existing.deleted)
        assertEquals(deletedAt, existing.sourceOccurredAt)
    }

    @Test
    fun `같은 DB microsecond의 member tombstone은 assignment upsert보다 우선함`() {
        val deletedAt = Instant.parse("2026-08-26T13:00:00.123456Z")
        val tombstoneKey = TeamRoleMemberTombstone.key(3L, 7L)
        val tombstone = TeamRoleMemberTombstone(
            projectionKey = tombstoneKey,
            teamId = 3L,
            accountId = 7L,
            sourceOccurredAt = deletedAt,
        )
        every { tombstoneRepository.findById(tombstoneKey) } returns Optional.of(tombstone)

        handler.handle(
            PreferenceTeamRoleChangedEvent(
                eventType = "ASSIGNMENT_UPSERTED",
                teamId = 3L,
                accountId = 7L,
                roleId = 9L,
                occurredAt = Instant.parse("2026-08-26T13:00:00.123456999Z"),
            ),
        )

        verify(exactly = 0) { assignmentRepository.findById(any()) }
        verify(exactly = 0) { assignmentRepository.save(any()) }
    }
}
