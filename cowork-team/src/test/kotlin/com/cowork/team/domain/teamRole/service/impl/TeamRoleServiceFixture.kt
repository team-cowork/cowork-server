package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamAccess.LockedTeam
import com.cowork.team.domain.teamAccess.MembershipDirectory
import com.cowork.team.domain.teamAccess.TeamDirectory
import com.cowork.team.domain.teamRole.operation.TeamRoleCommandSubmission
import com.cowork.team.domain.teamRole.operation.TeamRoleOperationStatus
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRoleLookupSupport
import com.cowork.team.global.policy.PermissionKeys
import com.cowork.team.global.policy.TeamAccessProjection
import io.mockk.every
import io.mockk.mockk

/** 실제 TeamRoleAccessGuard를 쓰고 저장소와 projection만 대체해, 서비스에서 검사 호출이 빠지면 테스트가 실패하게 한다. */
class TeamRoleServiceFixture {
    val team = LockedTeam(TEAM_ID, "team", OWNER_ID)
    val teamDirectory: TeamDirectory = mockk()
    val membershipDirectory: MembershipDirectory = mockk()
    val roleReader: TeamRoleProjectionReader = mockk()
    val commandSubmission: TeamRoleCommandSubmission = mockk()
    val access = mockk<TeamAccessProjection>()
    val guard = TeamRoleAccessGuard(teamDirectory, membershipDirectory, access, roleReader)
    val lookup = TeamRoleLookupSupport(roleReader)
    val accepted = TeamRoleOperationResponse("operation-1", TeamRoleOperationStatus.PENDING)

    init {
        every { teamDirectory.lock(TEAM_ID) } returns team
        every { membershipDirectory.exists(TEAM_ID, any()) } returns true
        every { access.permissions(TEAM_ID, any()) } returns emptySet()
        every { roleReader.findRole(TEAM_ID, any()) } returns null
        every {
            commandSubmission.submit(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            lastArg<() -> Unit>().invoke()
            accepted
        }
    }

    fun givenOwner() {
        every { access.permissions(TEAM_ID, OWNER_ID) } returns
            setOf("manage_roles", "administrator", "transfer_team_ownership")
        every { roleReader.getMemberRoles(TEAM_ID, OWNER_ID) } returns
            listOf(
                role(
                    priority = Int.MAX_VALUE,
                    permissions = mapOf(
                        "administrator" to true,
                        "transfer_team_ownership" to true,
                    ),
                ),
            )
    }

    fun givenCustomManager(
        priority: Int = MANAGER_PRIORITY,
        permissions: Map<String, Boolean> = mapOf(PermissionKeys.MANAGE_ROLES to true),
    ) {
        every { access.permissions(TEAM_ID, MANAGER_ID) } returns permissions.filterValues { it }.keys
        every { roleReader.getMemberRoles(TEAM_ID, MANAGER_ID) } returns
            listOf(role(id = 99L, priority = priority, permissions = permissions))
    }

    fun givenRole(role: TeamRoleResponse) {
        every { roleReader.findRole(TEAM_ID, role.id) } returns role
    }

    companion object {
        const val TEAM_ID = 1L
        const val OWNER_ID = 10L
        const val MANAGER_ID = 20L
        const val TARGET_ID = 30L
        const val ROLE_ID = 7L
        const val MANAGER_PRIORITY = 10

        fun role(
            id: Long = ROLE_ID,
            name: String = "Reviewer",
            priority: Int = MANAGER_PRIORITY - 1,
            permissions: Map<String, Boolean> = emptyMap(),
            isDefault: Boolean = false,
        ) = TeamRoleResponse(
            id = id,
            teamId = TEAM_ID,
            name = name,
            colorHex = "#123ABC",
            priority = priority,
            mentionable = true,
            permissions = permissions,
            createdAt = null,
            updatedAt = null,
            isDefault = isDefault,
        )
    }
}
