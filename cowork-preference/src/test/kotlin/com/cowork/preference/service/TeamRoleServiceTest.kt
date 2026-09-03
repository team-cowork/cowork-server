package com.cowork.preference.service

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.ChannelRolePolicyState
import com.cowork.preference.domain.TeamRoleAssignmentState
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.domain.TeamRoleMemberFence
import com.cowork.preference.domain.TeamRoleState
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class TeamRoleServiceTest {

    private val channelRolePolicyRepository = mockk<ChannelRolePolicyRepository>(relaxed = true)

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
    fun `repeated missing role deletion advances and republishes the durable tombstone`() = runBlocking {
        val repository = mockk<TeamRoleRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val connection = mockk<SqlConnection>()
        val firstVersion = Instant.parse("2026-08-30T01:02:03.123456Z")
        val secondVersion = firstVersion.plusNanos(1_000)
        val events = mutableListOf<PreferenceEvent>()
        coEvery { repository.findRole(teamId, roleId) } returns null
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery {
            repository.deleteRoleWithTombstone(connection, teamId, roleId, any())
        } returns TeamRoleState(teamId, roleId, null, firstVersion) andThen
            TeamRoleState(teamId, roleId, null, secondVersion)
        coEvery { outboxRepository.enqueue(connection, capture(events)) } returns Unit
        val service = TeamRoleService(repository, outboxRepository, channelRolePolicyRepository)

        val first = service.deleteRole(teamId, roleId)
        val second = service.deleteRole(teamId, roleId)

        assertTrue(first.exceptionOrNull() is NoSuchElementException)
        assertTrue(second.exceptionOrNull() is NoSuchElementException)
        assertEquals(listOf(firstVersion, secondVersion), events.map(PreferenceEvent::occurredAt))
        assertTrue(events.all { it.payload.getString("eventType") == "ROLE_DELETED" })
    }

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
        val policyVersion = Instant.parse("2026-08-27T01:02:01.123456Z")
        val policy = ChannelRolePolicyState(
            teamId,
            21L,
            roleId,
            JsonObject().put("message_read", false),
            policyVersion,
        )
        val policyTombstone = policy.copy(
            permissions = null,
            stateOccurredAt = policyVersion.plusNanos(1_000),
        )
        val assignmentTombstones = targetAssignments.mapIndexed { index, assignment ->
            TeamRoleAssignmentState(
                assignment.teamId,
                assignment.accountId,
                assignment.roleId,
                null,
                policyVersion.plusSeconds(1).plusNanos(index * 1_000L),
            )
        }
        val roleTombstone = TeamRoleState(
            teamId,
            roleId,
            null,
            policyVersion.plusSeconds(2),
        )

        coEvery { repository.findRole(teamId, roleId) } returns role
        coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
            firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
        }
        coEvery { repository.findMemberRoles(connection, teamId) } returns targetAssignments + otherAssignment
        coEvery { channelRolePolicyRepository.findPoliciesByRole(connection, teamId, roleId) } returns listOf(policy)
        coEvery { channelRolePolicyRepository.deletePolicy(connection, teamId, 21L, roleId) } returns policyTombstone
        targetAssignments.zip(assignmentTombstones).forEach { (assignment, tombstone) ->
            coEvery {
                repository.deleteAssignment(
                    connection,
                    assignment.accountId,
                    assignment.teamId,
                    assignment.roleId,
                    any(),
                )
            } returns tombstone
        }
        coEvery {
            repository.deleteRoleWithTombstone(connection, teamId, roleId, any())
        } returns roleTombstone
        coEvery { outboxRepository.enqueueAll(connection, capture(events)) } returns Unit

        val result = TeamRoleService(repository, outboxRepository, channelRolePolicyRepository)
            .deleteRole(teamId, roleId)

        assertTrue(result.isSuccess)
        val stored = events.captured.toList()
        assertEquals(
            listOf("DELETE", "ASSIGNMENT_DELETED", "ASSIGNMENT_DELETED", "ROLE_DELETED"),
            stored.map { it.payload.getString("eventType") },
        )
        assertEquals(
            listOf("policy:3:21:7", "assignment:3:11:7", "assignment:3:12:7", "role:3:7"),
            stored.map { it.key },
        )
        coVerifyOrder {
            repository.findMemberRoles(connection, teamId)
            channelRolePolicyRepository.findPoliciesByRole(connection, teamId, roleId)
            channelRolePolicyRepository.deletePolicy(connection, teamId, 21L, roleId)
            repository.deleteAssignment(connection, 11L, teamId, roleId, any())
            repository.deleteAssignment(connection, 12L, teamId, roleId, any())
            repository.deleteRoleWithTombstone(connection, teamId, roleId, any())
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
        val fenceVersion = assignmentStateVersion.toInstant().plusNanos(1_000)
        val assignmentTombstoneVersion = fenceVersion.plusNanos(1_000)
        val events = slot<Iterable<PreferenceEvent>>()
        coEvery {
            repository.removeMemberRolesAtOrBefore(connection, removed.accountId, teamId, deletionVersion)
        } returns listOf(removed)
        coEvery {
            repository.upsertMemberFence(connection, teamId, removed.accountId, fenceVersion)
        } returns TeamRoleMemberFence(teamId, removed.accountId, fenceVersion)
        coEvery {
            repository.deleteAssignment(
                connection,
                removed.accountId,
                teamId,
                roleId,
                fenceVersion,
            )
        } returns TeamRoleAssignmentState(
            teamId,
            removed.accountId,
            roleId,
            null,
            assignmentTombstoneVersion,
        )
        coEvery { outboxRepository.enqueueAll(connection, capture(events)) } returns Unit

        TeamRoleService(repository, outboxRepository, channelRolePolicyRepository).removeMemberRolesAtOrBefore(
            connection,
            removed.accountId,
            teamId,
            deletionVersion,
        )

        val stored = events.captured.toList()
        assertEquals(
            listOf("ASSIGNMENT_DELETED", "MEMBER_ASSIGNMENTS_DELETED"),
            stored.map { it.payload.getString("eventType") },
        )
        assertTrue(stored.all { it.occurredAt.isAfter(deletionVersion) })
        assertTrue(stored.all { it.occurredAt.isAfter(assignmentStateVersion.toInstant()) })
        assertEquals(
            listOf(assignmentTombstoneVersion, fenceVersion),
            stored.map(PreferenceEvent::occurredAt),
        )
        coVerifyOrder {
            repository.removeMemberRolesAtOrBefore(connection, removed.accountId, teamId, deletionVersion)
            repository.upsertMemberFence(connection, teamId, removed.accountId, fenceVersion)
            repository.deleteAssignment(connection, removed.accountId, teamId, roleId, fenceVersion)
            outboxRepository.enqueueAll(connection, any())
        }
    }

    @Test
    fun `member removal with no assignments still persists and publishes a durable fence`() = runBlocking {
        val repository = mockk<TeamRoleRepository>()
        val outboxRepository = mockk<PreferenceOutboxRepository>()
        val connection = mockk<SqlConnection>()
        val accountId = 11L
        val deletionVersion = Instant.parse("2026-08-27T01:02:03.123456Z")
        val fenceVersion = deletionVersion.plusNanos(1_000)
        val events = slot<Iterable<PreferenceEvent>>()
        coEvery {
            repository.removeMemberRolesAtOrBefore(connection, accountId, teamId, deletionVersion)
        } returns emptyList()
        coEvery {
            repository.upsertMemberFence(connection, teamId, accountId, fenceVersion)
        } returns TeamRoleMemberFence(teamId, accountId, fenceVersion)
        coEvery { outboxRepository.enqueueAll(connection, capture(events)) } returns Unit

        TeamRoleService(repository, outboxRepository, channelRolePolicyRepository).removeMemberRolesAtOrBefore(
            connection,
            accountId,
            teamId,
            deletionVersion,
        )

        val event = events.captured.single()
        assertEquals("MEMBER_ASSIGNMENTS_DELETED", event.payload.getString("eventType"))
        assertEquals(fenceVersion, event.occurredAt)
        coVerifyOrder {
            repository.removeMemberRolesAtOrBefore(connection, accountId, teamId, deletionVersion)
            repository.upsertMemberFence(connection, teamId, accountId, fenceVersion)
            outboxRepository.enqueueAll(connection, any())
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
        val policy = ChannelRolePolicyState(
            teamId,
            21L,
            roleId,
            JsonObject().put("message_read", true),
            roleVersion.toInstant(),
        )
        val policyTombstone = policy.copy(
            permissions = null,
            stateOccurredAt = roleVersion.toInstant().plusNanos(1_000),
        )
        val fenceVersion = assignmentVersion.toInstant().plusNanos(1_000)
        val assignmentTombstoneVersion = roleVersion.toInstant().plusNanos(2_000)
        val roleTombstoneVersion = roleVersion.toInstant().plusNanos(3_000)
        val events = slot<Iterable<PreferenceEvent>>()
        coEvery { repository.markTeamDeleted(connection, teamId, deletionVersion) } returns Unit
        coEvery { repository.findMemberRoles(connection, teamId) } returns listOf(assignment)
        coEvery { repository.findRoles(connection, teamId) } returns listOf(currentRole)
        coEvery { channelRolePolicyRepository.findPoliciesByTeam(connection, teamId) } returns listOf(policy)
        coEvery { channelRolePolicyRepository.deletePolicy(connection, teamId, 21L, roleId) } returns policyTombstone
        coEvery {
            repository.upsertMemberFence(connection, teamId, assignment.accountId, fenceVersion)
        } returns TeamRoleMemberFence(teamId, assignment.accountId, fenceVersion)
        coEvery {
            repository.deleteAssignment(
                connection,
                assignment.accountId,
                teamId,
                roleId,
                fenceVersion,
            )
        } returns TeamRoleAssignmentState(
            teamId,
            assignment.accountId,
            roleId,
            null,
            assignmentTombstoneVersion,
        )
        coEvery {
            repository.deleteRoleWithTombstone(connection, teamId, roleId, deletionVersion)
        } returns TeamRoleState(teamId, roleId, null, roleTombstoneVersion)
        coEvery { outboxRepository.enqueueAll(connection, capture(events)) } returns Unit

        TeamRoleService(repository, outboxRepository, channelRolePolicyRepository)
            .deleteTeamRoles(connection, teamId, deletionVersion)

        val stored = events.captured.toList()
        assertEquals(
            listOf("DELETE", "ASSIGNMENT_DELETED", "MEMBER_ASSIGNMENTS_DELETED", "ROLE_DELETED"),
            stored.map {
                it.payload.getString("eventType")
            },
        )
        assertEquals(assignmentTombstoneVersion, stored[1].occurredAt)
        assertEquals(fenceVersion, stored[2].occurredAt)
        assertEquals(roleTombstoneVersion, stored[3].occurredAt)
        coVerifyOrder {
            repository.markTeamDeleted(connection, teamId, deletionVersion)
            repository.findMemberRoles(connection, teamId)
            repository.findRoles(connection, teamId)
            channelRolePolicyRepository.findPoliciesByTeam(connection, teamId)
            channelRolePolicyRepository.deletePolicy(connection, teamId, 21L, roleId)
            repository.upsertMemberFence(connection, teamId, assignment.accountId, fenceVersion)
            repository.deleteAssignment(connection, assignment.accountId, teamId, roleId, fenceVersion)
            repository.deleteRoleWithTombstone(connection, teamId, roleId, deletionVersion)
            outboxRepository.enqueueAll(connection, any())
        }
    }
}
