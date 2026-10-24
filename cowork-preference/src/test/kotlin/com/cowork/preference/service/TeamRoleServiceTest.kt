package com.cowork.preference.service

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleAssignmentState
import com.cowork.preference.domain.PermissionKeys
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.domain.TeamRolePermissions
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProjectAccessStateRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
        service = TeamRoleService(repository, outboxRepository, mockk<ChannelRolePolicyRepository>(relaxed = true), mockk<ProjectAccessStateRepository>(relaxed = true))
    }

    @Nested
    inner class CreateRole {

        @Test
        fun `이름이나 색상이 유효하지 않으면 역할을 저장하지 않는다`() = runBlocking {
            val blankName = service.createRole(teamId, " ", "#123ABC", 10, true, emptyMap())
            val invalidColor = service.createRole(teamId, "Backend", "blue", 10, true, emptyMap())

            assertTrue(blankName.exceptionOrNull() is IllegalArgumentException)
            assertTrue(invalidColor.exceptionOrNull() is IllegalArgumentException)
            coVerify(exactly = 0) { repository.insertRole(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `같은 이름의 역할이 있으면 중복 역할을 거부한다`() = runBlocking {
            coEvery { repository.findRoleByName(teamId, "Backend") } returns role()

            val result = service.createRole(teamId, "Backend", "#123ABC", 10, true, emptyMap())

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

    @Nested
    inner class DefaultRole {
        @Test
        fun `기본 역할의 삭제 직접 할당 이름과 우선순위 변경을 거부한다`() = runBlocking {
            coEvery { repository.findRole(teamId, roleId) } returns role("@everyone").copy(isDefault = true)
            assertTrue(service.deleteRole(teamId, roleId).isFailure)
            assertTrue(service.assignRole(11L, teamId, roleId, Instant.now()).isFailure)
            assertTrue(service.updateRole(teamId, roleId, "changed", null, null, null, null).isFailure)
            assertTrue(service.updateRole(teamId, roleId, null, null, 20, null, null).isFailure)
            coVerify(exactly = 0) { repository.updateRole(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.assignRole(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `생성된 기본 역할은 같은 트랜잭션의 outbox에 기록하고 이미 있으면 다시 기록하지 않는다`() = runBlocking {
            val defaultRole = role("@everyone").copy(isDefault = true, updatedAt = OffsetDateTime.parse("2026-10-25T00:00:00Z"))
            coEvery { repository.ensureDefaultRole(connection, teamId) } returnsMany listOf(defaultRole, null)
            service.ensureDefaultRole(connection, teamId)
            service.ensureDefaultRole(connection, teamId)
            coVerify(exactly = 1) { outboxRepository.enqueue(connection, match { it.payload.getBoolean("isDefault") == true }) }
        }
    }

    @Test
    fun `늦게 도착한 탈퇴는 이전 멤버십의 역할만 지우고 재가입 후 역할의 fence를 갱신하지 않는다`() = runBlocking {
        val leaveVersion = Instant.parse("2026-10-24T00:00:00Z")
        val oldAssignment = AccountTeamRole(11L, teamId, roleId, leaveVersion.minusSeconds(1), leaveVersion.atOffset(ZoneOffset.UTC))
        val deleted = TeamRoleAssignmentState(teamId, 11L, roleId, null, leaveVersion.plusSeconds(1))
        val events = slot<Iterable<PreferenceEvent>>()
        coEvery { repository.removeMemberRolesAtOrBefore(connection, 11L, teamId, leaveVersion) } returns listOf(oldAssignment)
        coEvery { repository.deleteAssignment(connection, 11L, teamId, roleId, any()) } returns deleted
        coEvery { outboxRepository.enqueueAll(connection, capture(events)) } returns Unit

        service.removeMemberRolesAtOrBefore(connection, 11L, teamId, leaveVersion, publishMemberFence = false)

        assertEquals(listOf("ASSIGNMENT_DELETED"), events.captured.map { it.payload.getString("eventType") })
        coVerify(exactly = 0) { repository.upsertMemberFence(any(), any(), any(), any()) }
        coVerify(exactly = 1) { repository.deleteAssignment(connection, 11L, teamId, roleId, any()) }
        coVerify(exactly = 0) { repository.deleteAssignment(connection, 11L, teamId, roleId + 1, any()) }
    }

    private fun role(name: String = "Backend") = TeamRoleDefinition(
        id = roleId,
        teamId = teamId,
        name = name,
        colorHex = "#123ABC",
        priority = 10,
        mentionable = true,
        permissions = TeamRolePermissions.canonicalize(mapOf(PermissionKeys.MESSAGE_READ to true)),
        createdAt = OffsetDateTime.parse("2026-08-30T01:02:03Z"),
        updatedAt = null,
    )
}
