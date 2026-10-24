package com.cowork.preference.service

import com.cowork.preference.domain.ChannelLifecycleProjection
import com.cowork.preference.domain.ChannelRolePolicyState
import com.cowork.preference.domain.InitialChannelRoleOverwrite
import com.cowork.preference.domain.InitialChannelRolePolicySeed
import com.cowork.preference.domain.InitialProjectReadGrant
import com.cowork.preference.domain.ProjectLifecycleProjection
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.messaging.ChannelLifecycleEvent
import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.ProjectLifecycleEvent
import com.cowork.preference.messaging.ProjectionReadiness
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.InitialResourceAccessRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class InitialResourceAccessServiceTest {
    private val client = mockk<SqlClient>()
    private lateinit var ledger: InitialResourceAccessRepository
    private lateinit var members: TeamMemberProjectionRepository
    private lateinit var roles: TeamRoleRepository
    private lateinit var policies: ChannelRolePolicyRepository
    private lateinit var projectRoles: ProjectRoleRepository
    private lateinit var outbox: PreferenceOutboxRepository
    private lateinit var readiness: ProjectionReadiness
    private lateinit var service: InitialResourceAccessService

    @BeforeEach
    fun setUp() {
        ledger = mockk(relaxed = true)
        members = mockk()
        roles = mockk(relaxed = true)
        policies = mockk(relaxed = true)
        projectRoles = mockk(relaxed = true)
        outbox = mockk(relaxed = true)
        readiness = mockk()
        service = InitialResourceAccessService(ledger, members, roles, policies, projectRoles, outbox, readiness)
        every { readiness.isReady } returns true
        coEvery { ledger.claim(client, any(), any()) } returns true
        coEvery { members.find(client, TEAM, ACTOR) } returns member
        coEvery { roles.findAccountRoles(client, TEAM, ACTOR) } returns listOf(role)
        coEvery { roles.findRoles(client, TEAM) } returns listOf(role)
        coEvery { policies.upsertPolicy(client, TEAM, CHANNEL, ROLE, any()) } coAnswers {
            ChannelRolePolicyState(TEAM, CHANNEL, ROLE, arg(4), VERSION.plusSeconds(1))
        }
    }

    @Test
    fun `채널 생성 권한으로 초기 읽기 정책을 만들며 역할 관리 권한은 요구하지 않는다`() = runBlocking {
        val event = slot<PreferenceEvent>()
        coEvery { outbox.enqueue(client, capture(event)) } returns Unit

        service.channel(client, channelEvent, channel)

        coVerify { policies.upsertPolicy(client, TEAM, CHANNEL, ROLE, JsonObject().put("message_read", true)) }
        assertEquals(true, event.captured.payload.getJsonObject("permissions").getBoolean("message_read"))
    }

    @Test
    fun `초기 정책은 거부 키도 생성자가 가진 기본 권한 범위로 제한한다`() = runBlocking {
        val seed = channelSeed.copy(overwrites = listOf(
            InitialChannelRoleOverwrite(ROLE, JsonObject().put("message_read", true).put("message_manage", false)),
        ))

        service.channel(client, channelEvent.copy(initialRolePolicySeed = seed), channel)

        coVerify(exactly = 0) { policies.upsertPolicy(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `다른 역할만 허용해 생성자가 비공개 채널을 읽을 수 없으면 초기화하지 않는다`() = runBlocking {
        coEvery { roles.findRoles(client, TEAM) } returns listOf(role, role.copy(id = ROLE + 1, isDefault = false))
        val seed = channelSeed.copy(overwrites = listOf(
            InitialChannelRoleOverwrite(ROLE + 1, JsonObject().put("message_read", true)),
        ))

        service.channel(client, channelEvent.copy(initialRolePolicySeed = seed), channel)

        coVerify(exactly = 0) { policies.upsertPolicy(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `원본에서 채널 생성 권한을 회수했으면 지연된 초기화가 권한을 부여하지 않는다`() = runBlocking {
        coEvery { roles.findAccountRoles(client, TEAM, ACTOR) } returns listOf(role.copy(permissions = emptyMap()))

        service.channel(client, channelEvent, channel)

        coVerify(exactly = 0) { policies.upsertPolicy(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `이미 수정 또는 삭제한 채널 정책을 초기 정책으로 되돌리지 않는다`() = runBlocking {
        coEvery { ledger.hasChannelPolicyHistory(client, TEAM, CHANNEL) } returns true

        service.channel(client, channelEvent, channel)

        coVerify(exactly = 0) { policies.upsertPolicy(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `한 번 처리한 초기 접근은 권한 회수 뒤 재발행되어도 다시 부여하지 않는다`() = runBlocking {
        coEvery { ledger.claim(client, any(), any()) } returns false

        service.channel(client, channelEvent, channel)
        assertFalse(service.project(client, projectEvent, project))

        coVerify(exactly = 0) { policies.upsertPolicy(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { projectRoles.assignRole(any(), any(), any(), any()) }
    }

    @Test
    fun `탈퇴 후 재가입 등 멤버 버전이 앞서면 이전 초기 권한을 다시 부여하지 않는다`() = runBlocking {
        coEvery { members.find(client, TEAM, ACTOR) } returns member.copy(sourceOccurredAt = VERSION.plusSeconds(1))

        service.channel(client, channelEvent, channel)
        assertFalse(service.project(client, projectEvent, project))

        coVerify(exactly = 0) { policies.upsertPolicy(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { projectRoles.assignRole(any(), any(), any(), any()) }
    }

    @Test
    fun `닉네임만 바뀌어 권한 버전이 같으면 생성 시 승인한 초기 접근을 허용한다`() = runBlocking {
        coEvery { members.find(client, TEAM, ACTOR) } returns member.copy(
            sourceOccurredAt = VERSION.plusSeconds(1), authorizationVersion = VERSION,
        )

        service.channel(client, channelEvent.copy(
            initialRolePolicySeed = channelSeed.copy(actorAuthorizationVersion = VERSION),
        ), channel)
        assertTrue(service.project(client, projectEvent.copy(
            initialReadGrant = InitialProjectReadGrant(ACTOR, VERSION, VERSION),
        ), project))

        coVerify { policies.upsertPolicy(client, TEAM, CHANNEL, ROLE, any()) }
        coVerify { projectRoles.assignRole(ACTOR, PROJECT, "초기 접근", client) }
    }

    @Test
    fun `역할 변경이나 재가입으로 권한 버전이 바뀌면 이전 초기 접근을 거부한다`() = runBlocking {
        coEvery { members.find(client, TEAM, ACTOR) } returns member.copy(
            sourceOccurredAt = VERSION.plusSeconds(1), authorizationVersion = VERSION.plusSeconds(1),
        )

        service.channel(client, channelEvent.copy(
            initialRolePolicySeed = channelSeed.copy(actorAuthorizationVersion = VERSION),
        ), channel)
        assertFalse(service.project(client, projectEvent.copy(
            initialReadGrant = InitialProjectReadGrant(ACTOR, VERSION, VERSION),
        ), project))

        coVerify(exactly = 0) { policies.upsertPolicy(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { projectRoles.assignRole(any(), any(), any(), any()) }
    }

    @Test
    fun `구형 초기 요청은 새 권한 버전으로 바꾸지 않고 당시 멤버 버전으로 검사한다`() = runBlocking {
        coEvery { members.find(client, TEAM, ACTOR) } returns member.copy(
            authorizationVersion = VERSION.minusSeconds(1),
        )
        assertTrue(service.project(client, projectEvent, project))

        coEvery { members.find(client, TEAM, ACTOR) } returns member.copy(
            sourceOccurredAt = VERSION.plusSeconds(1), authorizationVersion = VERSION.minusSeconds(1),
        )
        assertFalse(service.project(client, projectEvent, project))
        coVerify(exactly = 1) { projectRoles.assignRole(ACTOR, PROJECT, "초기 접근", client) }
    }

    @Test
    fun `새 구형 이벤트로 원본 권한 버전이 없어진 경우 이전 승인 버전을 재사용하지 않는다`() = runBlocking {
        coEvery { members.find(client, TEAM, ACTOR) } returns member.copy(sourceOccurredAt = VERSION.plusSeconds(1))

        assertFalse(service.project(client, projectEvent.copy(
            initialReadGrant = InitialProjectReadGrant(ACTOR, VERSION, VERSION),
        ), project))

        coVerify(exactly = 0) { projectRoles.assignRole(any(), any(), any(), any()) }
    }

    @Test
    fun `팀 상태가 뒤처지면 초기 접근을 완료하지 않고 트랜잭션 재시도를 요청한다`() {
        coEvery { members.find(client, TEAM, ACTOR) } returns member.copy(sourceOccurredAt = VERSION.minusSeconds(1))

        assertThrows<IllegalStateException> { runBlocking { service.channel(client, channelEvent, channel) } }

        coVerify(exactly = 0) { policies.upsertPolicy(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `팀 projection이 준비되지 않으면 기존 멤버 행만으로 초기 접근을 허용하지 않는다`() {
        every { readiness.isReady } returns false

        assertThrows<IllegalStateException> { runBlocking { service.project(client, projectEvent, project) } }

        coVerify(exactly = 0) { projectRoles.assignRole(any(), any(), any(), any()) }
    }

    @Test
    fun `비공개 프로젝트 생성자에게 일반 역할의 읽기만 부여하고 다른 권한은 상속한다`() = runBlocking {
        assertTrue(service.project(client, projectEvent, project))

        coVerify {
            projectRoles.insertRole(PROJECT, "초기 접근", JsonObject().put("read", true), 0, client)
            projectRoles.assignRole(ACTOR, PROJECT, "초기 접근", client)
        }
    }

    @Test
    fun `프로젝트 생성 권한이 회수됐으면 읽기만 남아 있어도 초기 역할을 부여하지 않는다`() = runBlocking {
        coEvery { roles.findAccountRoles(client, TEAM, ACTOR) } returns
            listOf(role.copy(permissions = mapOf("project_read" to true)))

        assertFalse(service.project(client, projectEvent, project))

        coVerify(exactly = 0) { projectRoles.insertRole(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `프로젝트 생성자와 다른 계정이나 이미 변경된 프로젝트 역할은 초기화하지 않는다`() = runBlocking {
        assertFalse(service.project(client, projectEvent, project.copy(createdBy = ACTOR + 1)))
        coEvery { ledger.hasProjectAccessHistory(client, PROJECT) } returns true
        assertFalse(service.project(client, projectEvent, project))

        coVerify(exactly = 0) { projectRoles.insertRole(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `구버전에서 접근 상태를 이미 만들었으면 수동 회수 여부를 추정해 초기 읽기를 되살리지 않는다`() = runBlocking {
        coEvery { ledger.hasProjectAccessHistory(client, PROJECT) } returns true

        assertFalse(service.project(client, projectEvent, project))

        coVerify { ledger.claim(client, "PROJECT", PROJECT) }
        coVerify(exactly = 0) { projectRoles.assignRole(any(), any(), any(), any()) }
    }

    @Test
    fun `삭제된 리소스는 초기 접근을 소비하고 권한을 부여하지 않는다`() = runBlocking {
        service.channel(client, channelEvent, channel.copy(deleted = true))
        assertFalse(service.project(client, projectEvent, project.copy(deleted = true)))

        coVerify { ledger.claim(client, "CHANNEL", CHANNEL) }
        coVerify { ledger.claim(client, "PROJECT", PROJECT) }
        coVerify(exactly = 0) { policies.upsertPolicy(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { projectRoles.assignRole(any(), any(), any(), any()) }
    }

    @Test
    fun `초기 권한 outbox 실패를 삼키지 않아 처리 기록과 정책을 함께 롤백하게 한다`() {
        val failure = IllegalStateException("outbox unavailable")
        coEvery { outbox.enqueue(client, any()) } throws failure

        val error = assertThrows<IllegalStateException> {
            runBlocking { service.channel(client, channelEvent, channel) }
        }

        assertSame(failure, error)
    }

    companion object {
        private const val TEAM = 1L
        private const val ACTOR = 2L
        private const val ROLE = 3L
        private const val CHANNEL = 4L
        private const val PROJECT = 5L
        private val VERSION = Instant.parse("2026-10-25T00:00:00Z")
        private val member = TeamMemberProjection(TEAM, ACTOR, "MEMBER", false, VERSION)
        private val role = TeamRoleDefinition(
            id = ROLE, teamId = TEAM, name = "@everyone", colorHex = "#99AAB5", priority = Int.MIN_VALUE,
            mentionable = false,
            permissions = mapOf("manage_channels" to true, "message_read" to true,
                "manage_projects" to true, "project_read" to true),
            createdAt = null,
            updatedAt = null,
            isDefault = true,
        )
        private val channelSeed = InitialChannelRolePolicySeed(
            ACTOR, VERSION, listOf(InitialChannelRoleOverwrite(ROLE, JsonObject().put("message_read", true))),
        )
        private val channelEvent = ChannelLifecycleEvent(
            CHANNEL, TEAM, false, VERSION, true, "TEXT", channelSeed,
        )
        private val channel = ChannelLifecycleProjection(CHANNEL, TEAM, false, VERSION, true, "TEXT")
        private val projectEvent = ProjectLifecycleEvent(
            PROJECT, TEAM, false, VERSION, ACTOR, true, InitialProjectReadGrant(ACTOR, VERSION),
        )
        private val project = ProjectLifecycleProjection(PROJECT, TEAM, false, VERSION, ACTOR, true)
    }
}
