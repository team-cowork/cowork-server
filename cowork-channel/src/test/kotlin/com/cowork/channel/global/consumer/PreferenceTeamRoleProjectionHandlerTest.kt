package com.cowork.channel.global.consumer

import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjection
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleAssignmentProjectionRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstone
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleMemberTombstoneRepository
import com.cowork.channel.domain.channelRolePolicy.projection.TeamRoleProjectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class PreferenceTeamRoleProjectionHandlerTest {
    private val roleRepository = mockk<TeamRoleProjectionRepository>(relaxed = true)
    private val assignmentRepository = mockk<TeamRoleAssignmentProjectionRepository>(relaxed = true)
    private val tombstoneRepository = mockk<TeamRoleMemberTombstoneRepository>(relaxed = true)
    private val handler = PreferenceTeamRoleProjectionHandler(
        roleRepository,
        assignmentRepository,
        tombstoneRepository,
    )
    private val occurredAt = Instant.parse("2026-08-30T01:00:00.123456Z")

    @Test
    fun `동일 member tombstone 재처리도 assignment bulk delete를 다시 수행함`() {
        val tombstone = TeamRoleMemberTombstone("10:7", 10L, 7L, occurredAt)
        val stale = assignment(1L, occurredAt.minusSeconds(1))
        val newer = assignment(2L, occurredAt.plusSeconds(1))
        every { tombstoneRepository.findById("10:7") } returns Optional.of(tombstone)
        every { assignmentRepository.findAllByTeamIdAndAccountId(10L, 7L) } returns listOf(stale, newer)
        every { tombstoneRepository.save(any()) } answers { firstArg() }
        every { assignmentRepository.save(any()) } answers { firstArg() }

        handler.handle(
            PreferenceTeamRoleChangedEvent(
                eventType = "MEMBER_ASSIGNMENTS_DELETED",
                teamId = 10L,
                accountId = 7L,
                occurredAt = occurredAt,
            ),
        )

        assertTrue(stale.deleted)
        assertFalse(newer.deleted)
        verify { tombstoneRepository.save(tombstone) }
        verify { assignmentRepository.save(stale) }
        verify(exactly = 0) { assignmentRepository.save(newer) }
    }

    @Test
    fun `member tombstone과 같은 시각의 assignment upsert는 되살리지 않음`() {
        every { tombstoneRepository.findById("10:7") } returns Optional.of(
            TeamRoleMemberTombstone("10:7", 10L, 7L, occurredAt),
        )

        handler.handle(
            PreferenceTeamRoleChangedEvent(
                eventType = "ASSIGNMENT_UPSERTED",
                teamId = 10L,
                roleId = 1L,
                accountId = 7L,
                occurredAt = occurredAt,
            ),
        )

        verify(exactly = 0) { assignmentRepository.save(any()) }
    }

    private fun assignment(roleId: Long, version: Instant) = TeamRoleAssignmentProjection(
        projectionKey = "10:7:$roleId",
        teamId = 10L,
        accountId = 7L,
        roleId = roleId,
        sourceOccurredAt = version,
    )
}
