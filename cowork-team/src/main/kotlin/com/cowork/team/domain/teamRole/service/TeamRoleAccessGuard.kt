package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.global.policy.PermissionKeys
import com.cowork.team.global.policy.RolePolicy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

data class ManageRoleContext(val member: TeamMember, val roles: List<TeamRoleResponse>) {
    val maxPriority: Int = roles.maxOfOrNull { it.priority } ?: 0
    val administrator: Boolean = roles.any { RolePolicy.allows(it.permissions, "administrator") }
}

@Service
class TeamRoleAccessGuard(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamRoleProjectionReader: TeamRoleProjectionReader,
) {

    fun lockTeamOrThrow(teamId: Long) {
        if (teamRepository.findByIdForUpdate(teamId) == null) {
            throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        }
    }

    fun findMemberOrThrow(teamId: Long, userId: Long): TeamMember =
        teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

    fun requireMemberExists(teamId: Long, userId: Long) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }

    fun requireManageRoles(teamId: Long, userId: Long): ManageRoleContext =
        requirePermission(teamId, userId, PermissionKeys.MANAGE_ROLES, "역할 관리 권한이 없습니다.")

    /** 역할 이름과 관계없이 부여된 정책으로 작업 권한을 판정한다. */
    fun requirePermission(teamId: Long, userId: Long, permission: String, deniedMessage: String): ManageRoleContext {
        val member = findMemberOrThrow(teamId, userId)
        val roles = teamRoleProjectionReader.getMemberRoles(teamId, userId)
        if (roles.any { RolePolicy.allows(it.permissions, permission) }) {
            return ManageRoleContext(member, roles)
        }

        throw ExpectedException(deniedMessage, HttpStatus.FORBIDDEN)
    }

    fun requireGrantablePermissions(actor: ManageRoleContext, permissions: Map<String, Boolean>) {
        if (permissions["transfer_team_ownership"] == true) {
            throw ExpectedException("소유권 이양 권한은 소유자 정책에서만 부여됩니다.", HttpStatus.FORBIDDEN)
        }
        if (actor.administrator) return
        val allowed = actor.roles.flatMap { it.permissions.filterValues { value -> value }.keys }.toSet()
        if (permissions.any { (key, value) -> value && key !in allowed }) {
            throw ExpectedException("자신이 보유하지 않은 권한은 부여할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    fun requireCustomRole(role: TeamRoleResponse) {
        if (role.permissions["transfer_team_ownership"] ==
            true
        ) {
            throw ExpectedException("소유자 정책은 소유권 이양으로만 변경할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        if (role.isDefault) throw ExpectedException("기본 역할은 삭제하거나 직접 할당 및 해제할 수 없습니다.", HttpStatus.BAD_REQUEST)
    }

    fun requireDefaultRoleIdentity(role: TeamRoleResponse, name: String?, priority: Int?) {
        if (role.isDefault &&
            ((name != null && name != role.name) || (priority != null && priority != role.priority))
        ) {
            throw ExpectedException("기본 역할의 이름과 우선순위는 변경할 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
    }

    fun requireManageablePriority(actor: ManageRoleContext, role: TeamRoleResponse) {
        if (role.permissions["transfer_team_ownership"] == true) {
            throw ExpectedException("소유자 정책은 소유권 이양으로만 변경할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        if (role.priority == Int.MAX_VALUE) {
            throw ExpectedException("최상위 우선순위는 소유자 정책에 예약되어 있습니다.", HttpStatus.BAD_REQUEST)
        }
        if (actor.administrator) return

        if (role.priority >= actor.maxPriority) {
            throw ExpectedException("자신의 최상위 역할보다 높거나 같은 역할은 관리할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
