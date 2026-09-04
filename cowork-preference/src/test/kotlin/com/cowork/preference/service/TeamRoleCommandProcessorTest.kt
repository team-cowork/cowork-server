package com.cowork.preference.service

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.ProjectionReadiness
import com.cowork.preference.messaging.TeamRoleCommand
import com.cowork.preference.messaging.TeamRoleCommandRoleInput
import com.cowork.preference.messaging.TeamRoleCommandType
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProcessedTeamRoleCommandMatches
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleCommandInboxRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class TeamRoleCommandProcessorTest {

    private lateinit var fixture: Fixture

    @BeforeEach
    fun setUp() {
        fixture = Fixture()
    }

    @Nested
    inner class CreateRole {

        @Test
        fun `팀 OWNER가 유효하고 중복되지 않은 역할을 만들면 역할을 생성한다`() = runBlocking {
            fixture.stubCommand(createCommand, actorRole = "OWNER")
            coEvery {
                fixture.roleRepository.findRoleByName(fixture.connection, createCommand.teamId, "Reviewer")
            } returns null
            coEvery {
                fixture.roleRepository.insertRole(
                    fixture.connection,
                    createCommand.teamId,
                    "Reviewer",
                    "#5865F2",
                    10,
                    true,
                    setOf("MANAGE_ROLES"),
                )
            } returns role(9L, permissions = setOf("MANAGE_ROLES"))

            fixture.processor.process(createCommand)

            coVerify(exactly = 1) {
                fixture.roleRepository.insertRole(
                    fixture.connection,
                    createCommand.teamId,
                    "Reviewer",
                    "#5865F2",
                    10,
                    true,
                    setOf("MANAGE_ROLES"),
                )
            }
        }

        @Test
        fun `일반 멤버에게 역할 관리 권한이 없으면 역할을 생성하지 않는다`() = runBlocking {
            val result = slot<JsonObject>()
            fixture.stubCommand(createCommand, actorRole = "MEMBER")
            coEvery { fixture.roleRepository.findMemberRoles(fixture.connection, createCommand.teamId) } returns emptyList()
            coEvery { fixture.roleRepository.findRoles(fixture.connection, createCommand.teamId) } returns emptyList()
            coEvery { fixture.inboxRepository.insert(fixture.connection, createCommand, capture(result)) } returns Unit

            fixture.processor.process(createCommand)

            assertEquals("FORBIDDEN", result.captured.getJsonObject("error").getString("code"))
            coVerify(exactly = 0) { fixture.roleRepository.insertRole(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `사용자 정의 역할 관리자는 자신의 최상위 우선순위 이상 역할을 만들 수 없다`() = runBlocking {
            val result = slot<JsonObject>()
            fixture.stubCommand(createCommand, actorRole = "MEMBER")
            coEvery { fixture.roleRepository.findMemberRoles(fixture.connection, createCommand.teamId) } returns
                listOf(AccountTeamRole(createCommand.actorId, createCommand.teamId, 20L))
            coEvery { fixture.roleRepository.findRoles(fixture.connection, createCommand.teamId) } returns
                listOf(role(20L, priority = 10, permissions = setOf("MANAGE_ROLES")))
            coEvery { fixture.inboxRepository.insert(fixture.connection, createCommand, capture(result)) } returns Unit

            fixture.processor.process(createCommand)

            assertEquals("FORBIDDEN", result.captured.getJsonObject("error").getString("code"))
            coVerify(exactly = 0) { fixture.roleRepository.insertRole(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `같은 이름의 역할이 이미 있으면 중복 역할을 생성하지 않는다`() = runBlocking {
            val result = slot<JsonObject>()
            fixture.stubCommand(createCommand, actorRole = "OWNER")
            coEvery {
                fixture.roleRepository.findRoleByName(fixture.connection, createCommand.teamId, "Reviewer")
            } returns role(30L)
            coEvery { fixture.inboxRepository.insert(fixture.connection, createCommand, capture(result)) } returns Unit

            fixture.processor.process(createCommand)

            assertEquals("DUPLICATE_ROLE_NAME", result.captured.getJsonObject("error").getString("code"))
            coVerify(exactly = 0) { fixture.roleRepository.insertRole(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class AssignRole {

        @Test
        fun `할당 대상이 팀에서 제거됐으면 역할을 할당하지 않는다`() = runBlocking {
            val result = slot<JsonObject>()
            fixture.stubCommand(assignCommand, actorRole = "OWNER")
            coEvery {
                fixture.memberRepository.find(
                    fixture.connection,
                    assignCommand.teamId,
                    requireNotNull(assignCommand.targetAccountId),
                )
            } returns member(
                requireNotNull(assignCommand.targetAccountId),
                requireNotNull(assignCommand.targetMembershipVersion),
                deleted = true,
            )
            coEvery { fixture.inboxRepository.insert(fixture.connection, assignCommand, capture(result)) } returns Unit

            fixture.processor.process(assignCommand)

            assertEquals("TARGET_NOT_MEMBER", result.captured.getJsonObject("error").getString("code"))
            coVerify(exactly = 0) { fixture.roleRepository.assignRole(any(), any(), any(), any(), any()) }
        }
    }

    private class Fixture {
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val inboxRepository = mockk<TeamRoleCommandInboxRepository>(relaxed = true)
        val outboxRepository = mockk<PreferenceOutboxRepository>(relaxed = true)
        val readiness = mockk<ProjectionReadiness>()
        val connection = mockk<SqlConnection>()
        val processor = TeamRoleCommandProcessor(
            roleRepository,
            memberRepository,
            inboxRepository,
            outboxRepository,
            readiness,
            mockk<ChannelRolePolicyRepository>(relaxed = true),
        )

        fun stubCommand(command: TeamRoleCommand, actorRole: String) {
            every { readiness.isReady } returns true
            coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
            }
            coEvery { inboxRepository.findMatches(connection, command) } returns
                ProcessedTeamRoleCommandMatches(null, null)
            coEvery { roleRepository.isTeamDeleted(connection, command.teamId) } returns false
            coEvery { memberRepository.find(connection, command.teamId, command.actorId) } returns
                member(command.actorId, command.actorMembershipVersion, builtInRole = actorRole)
        }
    }

    private companion object {
        val createCommand = TeamRoleCommand(
            operationId = "6c4d7dd4-02fb-4ea9-9e28-e2ad548cce0e",
            idempotencyKey = "role-create-1",
            requestHash = "a".repeat(64),
            commandType = TeamRoleCommandType.CREATE,
            teamId = 3L,
            actorId = 7L,
            actorMembershipVersion = Instant.parse("2026-08-27T01:00:00Z"),
            targetAccountId = null,
            targetMembershipVersion = null,
            roleId = null,
            role = TeamRoleCommandRoleInput(
                name = "Reviewer",
                colorHex = "#5865F2",
                priority = 10,
                mentionable = true,
                permissions = setOf("MANAGE_ROLES"),
            ),
            submittedAt = Instant.parse("2026-08-27T01:01:00Z"),
        )
        val assignCommand = createCommand.copy(
            operationId = "a01769e0-9b8e-4c8f-a006-3bc76d5ea874",
            idempotencyKey = "role-assign-1",
            commandType = TeamRoleCommandType.ASSIGN,
            targetAccountId = 11L,
            targetMembershipVersion = Instant.parse("2026-08-27T01:00:30Z"),
            roleId = 9L,
            role = null,
        )

        fun member(
            accountId: Long,
            version: Instant,
            builtInRole: String = "MEMBER",
            deleted: Boolean = false,
        ) = TeamMemberProjection(
            teamId = createCommand.teamId,
            accountId = accountId,
            builtInRole = builtInRole,
            deleted = deleted,
            sourceOccurredAt = version,
        )

        fun role(
            id: Long,
            priority: Int = 10,
            permissions: Set<String> = emptySet(),
        ) = TeamRoleDefinition(
            id = id,
            teamId = createCommand.teamId,
            name = "Reviewer",
            colorHex = "#5865F2",
            priority = priority,
            mentionable = true,
            permissions = permissions,
            createdAt = OffsetDateTime.parse("2026-08-27T01:02:03Z"),
            updatedAt = null,
        )
    }
}
