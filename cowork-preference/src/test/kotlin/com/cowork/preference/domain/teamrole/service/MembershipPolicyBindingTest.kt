package com.cowork.preference.domain.teamrole.service

import com.cowork.preference.domain.access.permission.RolePolicy
import com.cowork.preference.domain.access.projection.team.model.TeamMemberEvent
import com.cowork.preference.domain.teamrole.model.AccountTeamRole
import com.cowork.preference.domain.teamrole.model.TeamRoleAssignmentState
import com.cowork.preference.domain.teamrole.model.TeamRoleDefinition
import com.cowork.preference.domain.teamrole.repository.TeamRoleRepository
import com.cowork.preference.global.outbox.PreferenceOutboxRepository
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vertx.sqlclient.SqlClient
import java.time.Instant
import java.time.ZoneOffset

class MembershipPolicyBindingTest :
    DescribeSpec({
        val version = Instant.parse("2026-11-17T00:00:00Z")
        val owner = TeamRoleDefinition(1L, 3L, "Owner", "#99AAB5", Int.MAX_VALUE, false, RolePolicy.owner(), null, null)
        val admin = owner.copy(
            id = 2L,
            name = "Admin",
            priority = Int.MAX_VALUE - 1,
            permissions = RolePolicy.administrator(),
        )
        val event = TeamMemberEvent("UPSERT", 3L, 7L, "MEMBER", version, version)
        lateinit var repository: TeamRoleRepository
        lateinit var outbox: PreferenceOutboxRepository
        lateinit var service: TeamRoleService
        val client = mockk<SqlClient>()

        beforeEach {
            repository = mockk()
            outbox = mockk(relaxed = true)
            service = TeamRoleService(repository, outbox, mockk())
            coEvery { repository.ensureManagedPolicy(client, 3L, any()) } returns null
            coEvery { repository.findManagedPolicy(client, 3L, true) } returns owner
            coEvery { repository.findManagedPolicy(client, 3L, false) } returns admin
            coEvery { repository.markPolicyMigrated(client, 3L, 7L) } returns false
            coEvery { repository.findAssignment(client, 7L, 3L, 1L) } returns null
        }

        it("기존 ADMIN 이벤트가 다시 와도 회수된 관리자 정책을 복구하지 않는다") {
            service.synchronizeMembershipPolicies(client, event.copy(role = "ADMIN"))
            coVerify(exactly = 0) { repository.assignRole(any(), any(), any(), any(), any()) }
        }

        it("기존 ADMIN은 최초 이관에서만 실제 관리자 역할을 받는다") {
            coEvery { repository.markPolicyMigrated(client, 3L, 7L) } returns true
            coEvery { repository.assignRole(client, 7L, 3L, 2L, version) } returns admin
            coEvery { repository.findAssignment(client, 7L, 3L, 2L) } returns
                AccountTeamRole(7L, 3L, 2L, version, version.atOffset(ZoneOffset.UTC))
            service.synchronizeMembershipPolicies(client, event.copy(role = "ADMIN"))
            coVerify(exactly = 1) { repository.assignRole(client, 7L, 3L, 2L, version) }
            coVerify(exactly = 1) { outbox.enqueue(client, any()) }
        }

        it("소유권을 넘긴 사용자는 루트 정책을 잃고 관리자 정책을 받는다") {
            val assignment = AccountTeamRole(7L, 3L, 1L, version, version.atOffset(ZoneOffset.UTC))
            coEvery { repository.findAssignment(client, 7L, 3L, 1L) } returns assignment
            coEvery { repository.deleteAssignment(client, 7L, 3L, 1L, version) } returns
                TeamRoleAssignmentState(3L, 7L, 1L, null, version.plusNanos(1000))
            coEvery { repository.assignRole(client, 7L, 3L, 2L, version) } returns admin
            coEvery { repository.findAssignment(client, 7L, 3L, 2L) } returns assignment.copy(roleId = 2L)
            service.synchronizeMembershipPolicies(client, event)
            coVerify(exactly = 1) { repository.deleteAssignment(client, 7L, 3L, 1L, version) }
            coVerify(exactly = 1) { repository.assignRole(client, 7L, 3L, 2L, version) }
            coVerify(exactly = 2) { outbox.enqueue(client, any()) }
        }

        it("새 소유자는 소유자 정책을 받는다") {
            coEvery { repository.findAssignment(client, 7L, 3L, 1L) } returnsMany listOf(
                null,
                AccountTeamRole(7L, 3L, 1L, version, version.atOffset(ZoneOffset.UTC)),
            )
            coEvery { repository.assignRole(client, 7L, 3L, 1L, version) } returns owner
            service.synchronizeMembershipPolicies(client, event.copy(role = "OWNER"))
            coVerify(exactly = 1) { repository.assignRole(client, 7L, 3L, 1L, version) }
        }
    })
