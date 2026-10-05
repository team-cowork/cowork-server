package com.cowork.preference.service

import com.cowork.preference.domain.ChannelLifecycleProjection
import com.cowork.preference.domain.ChannelRolePolicyState
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.ChannelRolePolicyCommand
import com.cowork.preference.messaging.ChannelRolePolicyCommandType
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.ChannelRolePolicyCommandInboxRepository
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProcessedChannelRolePolicyCommandMatches
import com.cowork.preference.repository.TeamMemberProjectionRepository
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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class ChannelRolePolicyCommandProcessorTest {

    private lateinit var fixture: Fixture

    @BeforeEach
    fun setUp() {
        fixture = Fixture()
    }

    @Nested
    inner class Process {

        @Test
        fun `요청자가 활성 멤버이고 역할도 같은 팀에 있으면 정책을 변경한다`() = runBlocking {
            fixture.stubActiveCommand()
            coEvery {
                fixture.policyRepository.upsertPolicy(
                    fixture.connection,
                    command.teamId,
                    command.channelId,
                    command.roleId,
                    requireNotNull(command.permissions),
                )
            } returns ChannelRolePolicyState(
                command.teamId,
                command.channelId,
                command.roleId,
                command.permissions,
                Instant.parse("2026-08-30T01:02:05Z"),
            )

            fixture.processor.process(command)

            coVerify(exactly = 1) {
                fixture.policyRepository.upsertPolicy(
                    fixture.connection,
                    command.teamId,
                    command.channelId,
                    command.roleId,
                    requireNotNull(command.permissions),
                )
            }
        }

        @Test
        fun `요청한 역할이 같은 팀에 없으면 정책을 변경하지 않는다`() = runBlocking {
            val result = slot<JsonObject>()
            fixture.stubActiveCommand(role = null)
            coEvery { fixture.inboxRepository.insert(fixture.connection, command, capture(result)) } returns Unit

            fixture.processor.process(command)

            assertEquals("ROLE_NOT_FOUND", result.captured.getJsonObject("error").getString("code"))
            coVerify(exactly = 0) { fixture.policyRepository.upsertPolicy(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `요청자가 팀에서 제거됐으면 정책을 변경하지 않는다`() = runBlocking {
            val result = slot<JsonObject>()
            fixture.stubActiveCommand(actorDeleted = true)
            coEvery { fixture.inboxRepository.insert(fixture.connection, command, capture(result)) } returns Unit

            fixture.processor.process(command)

            assertEquals("ACTOR_NOT_MEMBER", result.captured.getJsonObject("error").getString("code"))
            coVerify(exactly = 0) { fixture.policyRepository.upsertPolicy(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `채널 삭제가 먼저 반영됐으면 대기하던 UPSERT를 CHANNEL_DELETED로 종료한다`() = runBlocking {
            val result = slot<JsonObject>()
            fixture.stubActiveCommand(channel = deletedChannel)
            coEvery { fixture.inboxRepository.insert(fixture.connection, command, capture(result)) } returns Unit

            fixture.processor.process(command)

            assertEquals("FAILED", result.captured.getString("status"))
            assertEquals("CHANNEL_DELETED", result.captured.getJsonObject("error").getString("code"))
            coVerify(exactly = 0) { fixture.policyRepository.upsertPolicy(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `채널 삭제가 먼저 반영됐으면 대기하던 DELETE도 CHANNEL_DELETED로 종료한다`() = runBlocking {
            val deleteCommand = command.copy(commandType = ChannelRolePolicyCommandType.DELETE, permissions = null)
            val result = slot<JsonObject>()
            fixture.stubActiveCommand(command = deleteCommand, channel = deletedChannel)
            coEvery {
                fixture.inboxRepository.insert(fixture.connection, deleteCommand, capture(result))
            } returns Unit

            fixture.processor.process(deleteCommand)

            assertEquals("CHANNEL_DELETED", result.captured.getJsonObject("error").getString("code"))
            coVerify(exactly = 0) { fixture.policyRepository.deletePolicy(any(), any(), any(), any()) }
        }

        @Test
        fun `채널이 요청한 팀에 속하지 않으면 정책을 변경하지 않는다`() = runBlocking {
            val result = slot<JsonObject>()
            fixture.stubActiveCommand(channel = activeChannel.copy(teamId = command.teamId + 1))
            coEvery { fixture.inboxRepository.insert(fixture.connection, command, capture(result)) } returns Unit

            fixture.processor.process(command)

            assertEquals("CHANNEL_TEAM_MISMATCH", result.captured.getJsonObject("error").getString("code"))
            coVerify(exactly = 0) { fixture.policyRepository.upsertPolicy(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `채널 projection이 아직 없으면 결과를 남기지 않고 재시도하도록 실패한다`() {
            fixture.stubActiveCommand(channel = null)

            assertThrows<ChannelRolePolicyProjectionNotReadyException> {
                runBlocking { fixture.processor.process(command) }
            }

            coVerify(exactly = 0) { fixture.inboxRepository.insert(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.policyRepository.upsertPolicy(any(), any(), any(), any(), any()) }
        }
    }

    private class Fixture {
        val policyRepository = mockk<ChannelRolePolicyRepository>(relaxed = true)
        val roleRepository = mockk<TeamRoleRepository>()
        val memberRepository = mockk<TeamMemberProjectionRepository>()
        val channelRepository = mockk<ChannelLifecycleProjectionRepository>()
        val inboxRepository = mockk<ChannelRolePolicyCommandInboxRepository>(relaxed = true)
        val outboxRepository = mockk<PreferenceOutboxRepository>(relaxed = true)
        val readiness = mockk<ProjectionReadinessView>()
        val connection = mockk<SqlConnection>()
        val processor = ChannelRolePolicyCommandProcessor(
            policyRepository,
            roleRepository,
            memberRepository,
            channelRepository,
            inboxRepository,
            outboxRepository,
            readiness,
        )

        fun stubActiveCommand(
            command: ChannelRolePolicyCommand = ChannelRolePolicyCommandProcessorTest.command,
            role: TeamRoleDefinition? = sameTeamRole,
            actorDeleted: Boolean = false,
            channel: ChannelLifecycleProjection? = activeChannel,
        ) {
            every { readiness.isReady } returns true
            coEvery { outboxRepository.inTransaction<Unit>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> Unit>().invoke(connection)
            }
            coEvery { inboxRepository.findMatches(connection, command) } returns
                ProcessedChannelRolePolicyCommandMatches(null, null)
            coEvery { channelRepository.findForShare(connection, command.channelId) } returns channel
            coEvery { roleRepository.isTeamDeleted(connection, command.teamId) } returns false
            coEvery { memberRepository.find(connection, command.teamId, command.actorId) } returns
                TeamMemberProjection(
                    teamId = command.teamId,
                    accountId = command.actorId,
                    builtInRole = "ADMIN",
                    deleted = actorDeleted,
                    sourceOccurredAt = command.actorMembershipVersion,
                )
            if (!actorDeleted) {
                coEvery { roleRepository.findRole(connection, command.teamId, command.roleId) } returns role
            }
        }
    }

    private companion object {
        val command = ChannelRolePolicyCommand(
            operationId = "00000000-0000-0000-0000-000000000001",
            idempotencyKey = "policy-upsert-1",
            requestHash = "a".repeat(64),
            commandType = ChannelRolePolicyCommandType.UPSERT,
            teamId = 7L,
            channelId = 8L,
            roleId = 9L,
            actorId = 10L,
            actorMembershipVersion = Instant.parse("2026-08-30T01:02:03Z"),
            permissions = JsonObject().put("message_read", false),
            submittedAt = Instant.parse("2026-08-30T01:02:04Z"),
        )
        val activeChannel = ChannelLifecycleProjection(
            channelId = command.channelId,
            teamId = command.teamId,
            deleted = false,
            sourceOccurredAt = Instant.parse("2026-08-30T01:00:00Z"),
        )
        val deletedChannel = activeChannel.copy(
            deleted = true,
            sourceOccurredAt = Instant.parse("2026-08-30T01:02:04Z"),
        )
        val sameTeamRole = TeamRoleDefinition(
            id = command.roleId,
            teamId = command.teamId,
            name = "Restricted",
            colorHex = "#123ABC",
            priority = 30,
            mentionable = false,
            permissions = emptySet(),
            createdAt = OffsetDateTime.parse("2026-08-30T01:00:00Z"),
            updatedAt = null,
        )
    }
}
