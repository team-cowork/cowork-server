package com.cowork.preference.service

import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class TeamRoleServiceTest {

    private val teamId = 3L
    private val roleId = 7L
    private lateinit var repository: TeamRoleRepository
    private lateinit var outboxRepository: PreferenceOutboxRepository
    private lateinit var connection: SqlConnection
    private lateinit var service: TeamRoleService

    @BeforeEach
    fun setUp() {
        repository = mockk()
        outboxRepository = mockk(relaxed = true)
        connection = mockk()
        service = TeamRoleService(repository, outboxRepository, mockk<ChannelRolePolicyRepository>(relaxed = true))
    }

    @Nested
    inner class CreateRole {

        @Test
        fun `이름이나 색상이 유효하지 않으면 역할을 저장하지 않는다`() = runBlocking {
            val blankName = service.createRole(teamId, " ", "#123ABC", 10, true, emptySet())
            val invalidColor = service.createRole(teamId, "Backend", "blue", 10, true, emptySet())

            assertTrue(blankName.exceptionOrNull() is IllegalArgumentException)
            assertTrue(invalidColor.exceptionOrNull() is IllegalArgumentException)
            coVerify(exactly = 0) { repository.insertRole(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `같은 이름의 역할이 있으면 중복 역할을 거부한다`() = runBlocking {
            coEvery { repository.findRoleByName(teamId, "Backend") } returns role()

            val result = service.createRole(teamId, "Backend", "#123ABC", 10, true, emptySet())

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            coVerify(exactly = 0) { repository.insertRole(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `유효하고 중복되지 않은 역할이면 새 역할을 반환한다`() = runBlocking {
            val persisted = role()
            coEvery { repository.findRoleByName(teamId, persisted.name) } returns null
            coEvery { outboxRepository.inTransaction<TeamRoleDefinition>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> TeamRoleDefinition>().invoke(connection)
            }
            coEvery {
                repository.insertRole(
                    connection,
                    teamId,
                    persisted.name,
                    persisted.colorHex,
                    persisted.priority,
                    persisted.mentionable,
                    persisted.permissions,
                )
            } returns persisted

            val result = service.createRole(
                teamId,
                persisted.name,
                persisted.colorHex,
                persisted.priority,
                persisted.mentionable,
                persisted.permissions,
            )

            assertEquals(persisted, result.getOrThrow())
        }
    }

    @Nested
    inner class UpdateRole {

        @Test
        fun `대상 역할이 없으면 not found 실패를 반환한다`() = runBlocking {
            coEvery { repository.findRole(teamId, roleId) } returns null

            val result = service.updateRole(teamId, roleId, null, null, null, null, null)

            assertTrue(result.exceptionOrNull() is NoSuchElementException)
        }

        @Test
        fun `변경할 이름이 다른 역할과 중복되면 변경을 거부한다`() = runBlocking {
            coEvery { repository.findRole(teamId, roleId) } returns role()
            coEvery { repository.findRoleByNameExceptId(teamId, "Frontend", roleId) } returns role("Frontend")

            val result = service.updateRole(teamId, roleId, "Frontend", null, null, null, null)

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            coVerify(exactly = 0) { repository.updateRole(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class AssignRole {

        @Test
        fun `대상 역할이 없으면 멤버에게 할당하지 않는다`() = runBlocking {
            coEvery { repository.findRole(teamId, roleId) } returns null

            val result = service.assignRole(11L, teamId, roleId, Instant.parse("2026-08-30T01:00:00Z"))

            assertTrue(result.exceptionOrNull() is NoSuchElementException)
            coVerify(exactly = 0) { repository.assignRole(any(), any(), any(), any(), any()) }
        }
    }

    private fun role(name: String = "Backend") = TeamRoleDefinition(
        id = roleId,
        teamId = teamId,
        name = name,
        colorHex = "#123ABC",
        priority = 10,
        mentionable = true,
        permissions = setOf("PROJECT_READ"),
        createdAt = OffsetDateTime.parse("2026-08-30T01:02:03Z"),
        updatedAt = null,
    )
}
