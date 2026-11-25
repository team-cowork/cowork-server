package com.cowork.preference.domain.access.effective.service

import com.cowork.preference.domain.access.effective.model.EffectivePermissionQuery
import com.cowork.preference.domain.access.effective.model.PermissionScope
import com.cowork.preference.domain.access.exception.ResourceAccessDeniedException
import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PolicyRole
import com.cowork.preference.domain.access.projection.channel.model.ChannelLifecycleProjection
import com.cowork.preference.domain.access.projection.project.model.ProjectLifecycleProjection
import com.cowork.preference.domain.access.service.ChannelAccessGuard
import com.cowork.preference.domain.access.service.ProjectAccessGuard
import com.cowork.preference.domain.access.service.TeamMembershipGuard
import com.cowork.preference.domain.accountrole.AccountRoleRepository
import com.cowork.preference.domain.channelrole.repository.ChannelRolePolicyRepository
import com.cowork.preference.domain.projectrole.model.ProjectPermissions
import com.cowork.preference.domain.projectrole.repository.ProjectRoleRepository
import com.cowork.preference.domain.teamrole.model.TeamRoleDefinition
import com.cowork.preference.domain.teamrole.repository.TeamRoleRepository
import com.cowork.preference.global.security.RequesterContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vertx.sqlclient.Pool
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val USER_ID = 100L
private const val TEAM_ID = 3L
private const val CHANNEL_ID = 45L
private const val PROJECT_ID = 7L
private val REQUESTER = RequesterContext(USER_ID)
private val NOW = Instant.parse("2026-11-24T05:12:00Z")

private class EffectivePermissionFixture {
    val pool = mockk<Pool>()
    val teamMembershipGuard = mockk<TeamMembershipGuard>()
    val channelAccessGuard = mockk<ChannelAccessGuard>()
    val projectAccessGuard = mockk<ProjectAccessGuard>()
    val accountRoleRepository = mockk<AccountRoleRepository>()
    val teamRoleRepository = mockk<TeamRoleRepository>()
    val channelRolePolicyRepository = mockk<ChannelRolePolicyRepository>()
    val projectRoleRepository = mockk<ProjectRoleRepository>()
    val service = EffectivePermissionService(
        pool,
        teamMembershipGuard,
        channelAccessGuard,
        projectAccessGuard,
        accountRoleRepository,
        teamRoleRepository,
        channelRolePolicyRepository,
        projectRoleRepository,
        Clock.fixed(NOW, ZoneOffset.UTC),
    )

    fun teamRoles(vararg permissions: Map<String, Boolean>) {
        coEvery { teamRoleRepository.findAccountRoles(pool, TEAM_ID, USER_ID) } returns
            permissions.mapIndexed { index, it ->
                TeamRoleDefinition(
                    index + 1L, TEAM_ID, "role-$index", "#99AAB5", index, false, it, null, null,
                    index == 0,
                )
            }
    }

    fun channel(teamId: Long?, isPrivate: Boolean = false, type: String = "TEXT", archived: Boolean = false) {
        coEvery { channelAccessGuard.requireChannelTeamMember(CHANNEL_ID, USER_ID) } returns
            ChannelLifecycleProjection(CHANNEL_ID, teamId, false, NOW, isPrivate, type, archived)
    }

    fun evaluate(scope: PermissionScope, explain: Boolean = false) = runBlocking {
        service.evaluate(REQUESTER, EffectivePermissionQuery(scope, emptySet(), explain))
    }
}

class EffectivePermissionServiceTest :
    DescribeSpec({
        describe("EffectivePermissionService 클래스의 evaluate 메서드는") {
            it("계정 범위에서 요청자의 계정 역할을 평가한다") {
                val fixture = EffectivePermissionFixture()
                coEvery { fixture.accountRoleRepository.policyRoles(fixture.pool, USER_ID) } returns
                    listOf(PolicyRole(1L, "default", mapOf("create_team" to true), isDefault = true))
                every { fixture.teamMembershipGuard.requireProjectionReady() } returns Unit
                coEvery { fixture.teamRoleRepository.findRolesInMemberTeams(fixture.pool, USER_ID) } returns emptyList()

                val result = fixture.evaluate(PermissionScope.Account)

                result.decisions.getValue("create_team").allowed shouldBe true
                result.decisions.getValue("manage_account_roles").allowed shouldBe false
                result.evaluatedAt shouldBe NOW
                result.teamId shouldBe null
            }

            it("계정 역할이 허용하지 않아도 소속 팀의 관리자 역할이 있으면 팀 생성을 허용한다") {
                val fixture = EffectivePermissionFixture()
                coEvery { fixture.accountRoleRepository.policyRoles(fixture.pool, USER_ID) } returns
                    listOf(PolicyRole(1L, "default", mapOf("create_team" to false), isDefault = true))
                every { fixture.teamMembershipGuard.requireProjectionReady() } returns Unit
                coEvery { fixture.teamRoleRepository.findRolesInMemberTeams(fixture.pool, USER_ID) } returns listOf(
                    TeamRoleDefinition(
                        9L, TEAM_ID, "Admin", "#99AAB5", 10, false,
                        mapOf(
                            "administrator" to true,
                        ),
                        null, null,
                    ),
                )

                val decision = fixture.evaluate(PermissionScope.Account).decisions.getValue("create_team")

                decision.allowed shouldBe true
                decision.decidedBy shouldBe PermissionDecisionReason.ADMINISTRATOR
                decision.sources.last().teamId shouldBe TEAM_ID
            }

            it("팀 생성 권한을 조회하지 않으면 소속 팀 역할을 읽지 않는다") {
                val fixture = EffectivePermissionFixture()
                coEvery { fixture.accountRoleRepository.policyRoles(fixture.pool, USER_ID) } returns emptyList()

                runBlocking {
                    fixture.service.evaluate(
                        REQUESTER,
                        EffectivePermissionQuery(PermissionScope.Account, setOf("manage_account_roles"), false),
                    )
                }

                coVerify(exactly = 0) { fixture.teamRoleRepository.findRolesInMemberTeams(any(), any()) }
            }

            it("팀 범위에서 멤버십을 확인한 뒤 팀 역할을 평가한다") {
                val fixture = EffectivePermissionFixture()
                coEvery { fixture.teamMembershipGuard.requireMember(TEAM_ID, REQUESTER) } returns Unit
                fixture.teamRoles(mapOf("manage_team" to false), mapOf("manage_team" to true))

                val result = fixture.evaluate(PermissionScope.Team(TEAM_ID))

                result.decisions.getValue("manage_team").allowed shouldBe true
                result.decisions.getValue("manage_roles").allowed shouldBe false
                result.teamId shouldBe TEAM_ID
            }

            it("팀 멤버가 아니면 역할을 읽지 않고 거부한다") {
                val fixture = EffectivePermissionFixture()
                coEvery { fixture.teamMembershipGuard.requireMember(TEAM_ID, REQUESTER) } throws
                    ResourceAccessDeniedException("팀 멤버만 접근할 수 있습니다.")

                shouldThrow<ResourceAccessDeniedException> { fixture.evaluate(PermissionScope.Team(TEAM_ID)) }
                coVerify(exactly = 0) { fixture.teamRoleRepository.findAccountRoles(any(), any(), any()) }
            }

            it("채널 범위에서 채널 소속 팀의 역할과 채널 덮어쓰기를 함께 평가한다") {
                val fixture = EffectivePermissionFixture()
                fixture.channel(TEAM_ID)
                fixture.teamRoles(mapOf("message_read" to true, "message_send" to true))
                coEvery {
                    fixture.channelRolePolicyRepository.findOverwritesForRead(fixture.pool, TEAM_ID, CHANNEL_ID)
                } returns
                    mapOf(1L to mapOf("message_send" to false))

                val result = fixture.evaluate(PermissionScope.Channel(CHANNEL_ID))

                result.teamId shouldBe TEAM_ID
                result.decisions.getValue("message_read").allowed shouldBe true
                result.decisions.getValue("message_send").decidedBy shouldBe PermissionDecisionReason.DEFAULT_OVERWRITE
            }

            it("채널 접근 권한이 없는 팀 멤버에게도 거부 사유와 본인 역할의 근거를 반환한다") {
                val fixture = EffectivePermissionFixture()
                fixture.channel(TEAM_ID, isPrivate = true)
                fixture.teamRoles(mapOf("message_read" to true))
                coEvery {
                    fixture.channelRolePolicyRepository.findOverwritesForRead(fixture.pool, TEAM_ID, CHANNEL_ID)
                } returns
                    emptyMap()

                val result = fixture.evaluate(PermissionScope.Channel(CHANNEL_ID), explain = true)

                val decision = result.decisions.getValue("message_read")
                decision.allowed shouldBe false
                decision.decidedBy shouldBe PermissionDecisionReason.PRIVATE_CHANNEL
                decision.sources.map { it.roleId } shouldBe listOf(1L)
            }

            it("채널 팀 멤버가 아니면 정책을 읽지 않고 거부한다") {
                val fixture = EffectivePermissionFixture()
                coEvery { fixture.channelAccessGuard.requireChannelTeamMember(CHANNEL_ID, USER_ID) } throws
                    ResourceAccessDeniedException("채널에 접근할 권한이 없습니다.")

                shouldThrow<ResourceAccessDeniedException> { fixture.evaluate(PermissionScope.Channel(CHANNEL_ID)) }
                coVerify(exactly = 0) { fixture.channelRolePolicyRepository.findOverwritesForRead(any(), any(), any()) }
            }

            it("보관된 채널은 판정 값을 바꾸지 않고 보관 여부만 표시한다") {
                val fixture = EffectivePermissionFixture()
                fixture.channel(TEAM_ID, archived = true)
                fixture.teamRoles(mapOf("message_read" to true, "message_send" to true))
                coEvery {
                    fixture.channelRolePolicyRepository.findOverwritesForRead(fixture.pool, TEAM_ID, CHANNEL_ID)
                } returns
                    emptyMap()

                val result = fixture.evaluate(PermissionScope.Channel(CHANNEL_ID))

                result.channelArchived shouldBe true
                result.decisions.getValue("message_send").allowed shouldBe true
            }

            it("DM 채널은 역할 권한이 없으므로 잘못된 요청으로 거부한다") {
                val fixture = EffectivePermissionFixture()
                fixture.channel(teamId = null)

                shouldThrow<IllegalArgumentException> { fixture.evaluate(PermissionScope.Channel(CHANNEL_ID)) }
            }

            it("프로젝트 범위에서 저장된 접근 상태를 판정 값으로 쓴다") {
                val fixture = EffectivePermissionFixture()
                coEvery { fixture.projectAccessGuard.requireTeamMember(PROJECT_ID, REQUESTER) } returns
                    ProjectLifecycleProjection(PROJECT_ID, TEAM_ID, false, NOW, isPrivate = false)
                fixture.teamRoles(mapOf("project_read" to true, "project_write" to true))
                coEvery { fixture.projectRoleRepository.findAccountRoles(PROJECT_ID, USER_ID, fixture.pool) } returns
                    emptyList()
                coEvery {
                    fixture.projectRoleRepository.effectivePermissions(PROJECT_ID, USER_ID, fixture.pool)
                } returns
                    ProjectPermissions(read = true)

                val result = fixture.evaluate(PermissionScope.Project(PROJECT_ID))

                result.teamId shouldBe TEAM_ID
                result.decisions.getValue("project_read").decidedBy shouldBe PermissionDecisionReason.ROLE_GRANT
                result.decisions.getValue("project_write").allowed shouldBe false
                result.decisions.getValue("project_write").decidedBy shouldBe
                    PermissionDecisionReason.ACCESS_STATE_PENDING
            }
        }
    })
