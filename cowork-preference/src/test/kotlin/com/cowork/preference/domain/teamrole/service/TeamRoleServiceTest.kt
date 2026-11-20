package com.cowork.preference.domain.teamrole.service

import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.channelrole.repository.ChannelRolePolicyRepository
import com.cowork.preference.domain.teamrole.model.AccountTeamRole
import com.cowork.preference.domain.teamrole.model.TeamRoleAssignmentState
import com.cowork.preference.domain.teamrole.model.TeamRoleDefinition
import com.cowork.preference.domain.teamrole.model.TeamRolePermissions
import com.cowork.preference.domain.teamrole.repository.TeamRoleRepository
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.outbox.PreferenceOutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
        service =
            TeamRoleService(
                repository,
                outboxRepository,
                mockk<ChannelRolePolicyRepository>(relaxed = true),
            )
    }

    @Nested
    inner class DefaultRole {
        @Test
        fun `생성된 기본 역할은 같은 트랜잭션의 outbox에 기록하고 이미 있으면 다시 기록하지 않는다`() = runBlocking {
            val defaultRole = role(
                "@everyone",
            ).copy(isDefault = true, updatedAt = OffsetDateTime.parse("2026-10-25T00:00:00Z"))
            coEvery { repository.ensureDefaultRole(connection, teamId) } returnsMany listOf(defaultRole, null)
            service.ensureDefaultRole(connection, teamId)
            service.ensureDefaultRole(connection, teamId)
            coVerify(exactly = 1) {
                outboxRepository.enqueue(
                    connection,
                    match {
                        it.payload.getBoolean("isDefault") ==
                            true
                    },
                )
            }
        }
    }

    @Test
    fun `늦게 도착한 탈퇴는 이전 멤버십의 역할만 지우고 재가입 후 역할의 fence를 갱신하지 않는다`() = runBlocking {
        val leaveVersion = Instant.parse("2026-10-24T00:00:00Z")
        val oldAssignment =
            AccountTeamRole(11L, teamId, roleId, leaveVersion.minusSeconds(1), leaveVersion.atOffset(ZoneOffset.UTC))
        val deleted = TeamRoleAssignmentState(teamId, 11L, roleId, null, leaveVersion.plusSeconds(1))
        val events = slot<Iterable<PreferenceEvent>>()
        coEvery { repository.removeMemberRolesAtOrBefore(connection, 11L, teamId, leaveVersion) } returns
            listOf(oldAssignment)
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
