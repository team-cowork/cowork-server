package com.cowork.preference.service

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class TeamRoleServiceTest {

    private val teamId = 3L
    private val roleId = 7L
    private val role = TeamRoleDefinition(
        id = roleId,
        teamId = teamId,
        name = "Backend",
        colorHex = "#123ABC",
        priority = 10,
        mentionable = true,
        permissions = setOf("PROJECT_READ"),
        createdAt = null,
        updatedAt = null,
    )

    @Test
    fun `role deletion stores ordered tombstones with the local deletion in one transaction`() = runBlocking {
        val repository = mockk<TeamRoleRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val connection = mockk<SqlConnection>()
        val targetAssignments = listOf(
            AccountTeamRole(accountId = 11L, teamId = teamId, roleId = roleId),
            AccountTeamRole(accountId = 12L, teamId = teamId, roleId = roleId),
        )
        val otherAssignment = AccountTeamRole(accountId = 13L, teamId = teamId, roleId = 8L)
        val events = slot<Iterable<PreferenceEvent>>()

        coEvery { repository.findRole(teamId, roleId) } returns role
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { repository.findMemberRoles(connection, teamId) } returns targetAssignments + otherAssignment
        coEvery { repository.deleteRole(connection, teamId, roleId) } returns Unit
        coEvery { outboxRepository.enqueueAll(connection, capture(events)) } returns Unit

        val result = TeamRoleService(repository, outboxRepository).deleteRole(teamId, roleId)

        assertTrue(result.isSuccess)
        val stored = events.captured.toList()
        assertEquals(
            listOf("ASSIGNMENT_DELETED", "ASSIGNMENT_DELETED", "ROLE_DELETED"),
            stored.map { it.payload.getString("eventType") },
        )
        assertEquals(listOf("assignment:3:11:7", "assignment:3:12:7", "role:3:7"), stored.map { it.key })
        assertEquals(1, stored.map(PreferenceEvent::occurredAt).distinct().size)
        coVerifyOrder {
            repository.findMemberRoles(connection, teamId)
            repository.deleteRole(connection, teamId, roleId)
            outboxRepository.enqueueAll(connection, any())
        }
    }

    @Test
    fun `member removal tombstone은 삭제 버전과 제거한 assignment state보다 항상 새롭다`() = runBlocking {
        val repository = mockk<TeamRoleRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val connection = mockk<SqlConnection>()
        val deletionVersion = Instant.parse("2026-08-27T01:02:03.123456Z")
        val assignmentStateVersion = OffsetDateTime.parse("2026-08-27T01:02:04.654321Z")
        val removed = AccountTeamRole(
            accountId = 11L,
            teamId = teamId,
            roleId = roleId,
            sourceMembershipVersion = deletionVersion.minusSeconds(1),
            updatedAt = assignmentStateVersion,
        )
        val event = slot<PreferenceEvent>()
        coEvery {
            repository.removeMemberRolesAtOrBefore(connection, removed.accountId, teamId, deletionVersion)
        } returns listOf(removed)
        coEvery { outboxRepository.enqueue(connection, capture(event)) } returns Unit

        TeamRoleService(repository, outboxRepository).removeMemberRolesAtOrBefore(
            connection,
            removed.accountId,
            teamId,
            deletionVersion,
        )

        assertEquals("MEMBER_ASSIGNMENTS_DELETED", event.captured.payload.getString("eventType"))
        assertTrue(event.captured.occurredAt.isAfter(deletionVersion))
        assertTrue(event.captured.occurredAt.isAfter(assignmentStateVersion.toInstant()))
        assertEquals(
            assignmentStateVersion.toInstant().plusNanos(1_000),
            event.captured.occurredAt,
        )
        coVerifyOrder {
            repository.removeMemberRolesAtOrBefore(connection, removed.accountId, teamId, deletionVersion)
            outboxRepository.enqueue(connection, any())
        }
    }

    @Test
    fun `team deletion은 영구 fence와 제거한 role state보다 새로운 tombstone을 함께 저장한다`() = runBlocking {
        val repository = mockk<TeamRoleRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val connection = mockk<SqlConnection>()
        val deletionVersion = Instant.parse("2026-08-27T01:02:03.123456Z")
        val assignmentVersion = OffsetDateTime.parse("2026-08-27T01:02:04.654321Z")
        val roleVersion = OffsetDateTime.parse("2026-08-27T01:02:05.111111Z")
        val assignment = AccountTeamRole(
            accountId = 11L,
            teamId = teamId,
            roleId = roleId,
            sourceMembershipVersion = deletionVersion.minusSeconds(1),
            updatedAt = assignmentVersion,
        )
        val currentRole = role.copy(updatedAt = roleVersion)
        val events = slot<Iterable<PreferenceEvent>>()
        coEvery { repository.markTeamDeleted(connection, teamId, deletionVersion) } returns Unit
        coEvery { repository.findMemberRoles(connection, teamId) } returns listOf(assignment)
        coEvery { repository.findRoles(connection, teamId) } returns listOf(currentRole)
        coEvery { repository.deleteTeamRoles(connection, teamId) } returns Unit
        coEvery { outboxRepository.enqueueAll(connection, capture(events)) } returns Unit

        TeamRoleService(repository, outboxRepository).deleteTeamRoles(connection, teamId, deletionVersion)

        val stored = events.captured.toList()
        assertEquals(listOf("ASSIGNMENT_DELETED", "ROLE_DELETED"), stored.map { it.payload.getString("eventType") })
        assertTrue(stored.all { it.occurredAt.isAfter(deletionVersion) })
        assertTrue(stored.all { it.occurredAt.isAfter(assignmentVersion.toInstant()) })
        assertTrue(stored.all { it.occurredAt.isAfter(roleVersion.toInstant()) })
        coVerifyOrder {
            repository.markTeamDeleted(connection, teamId, deletionVersion)
            repository.findMemberRoles(connection, teamId)
            repository.findRoles(connection, teamId)
            repository.deleteTeamRoles(connection, teamId)
            outboxRepository.enqueueAll(connection, any())
        }
    }
}
