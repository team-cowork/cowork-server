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
}
