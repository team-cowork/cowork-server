package com.cowork.preference.service

import com.cowork.preference.domain.PermissionKeys
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleRepository
import com.cowork.preference.security.RequesterContext
import io.vertx.sqlclient.Pool

/** 팀 멤버 projection이 아직 해당 팀/계정 상태를 따라잡지 못했을 때 던진다. 호출부는 503으로 매핑해야 한다. */
class TeamMembershipProjectionNotReadyException(message: String) : IllegalStateException(message)

/** 요청자가 팀 멤버가 아니거나 요구되는 관리 권한이 없을 때 던진다. 호출부는 403으로 매핑해야 한다. */
class TeamMembershipDeniedException(message: String) : RuntimeException(message)

/**
 * 팀 범위 Preference API 호출자가 대상 팀에 접근할 권한이 있는지 [TeamMemberProjectionRepository] projection으로 검증한다.
 * 활성 팀 멤버십을 먼저 확인한 뒤 기본 역할과 사용자 정의 역할의 권한을 합산한다.
 */
class TeamMembershipGuard(
    private val pool: Pool,
    private val memberRepository: TeamMemberProjectionRepository,
    private val readiness: ProjectionReadinessView,
    private val roleRepository: TeamRoleRepository,
) {
    /** 팀 조회 최소 권한: 활성 팀 멤버. */
    suspend fun requireMember(teamId: Long, requester: RequesterContext) {
        requireActiveMember(teamId, requester.userId)
    }

    /** 팀 설정은 manage_team, 닉네임 형식 키만 수정할 때는 manage_nickname_format도 허용한다. */
    suspend fun requireSettingsManager(teamId: Long, requester: RequesterContext, settingKeys: Set<String>) {
        val member = requireActiveMember(teamId, requester.userId)
        if (member.builtInRole in SETTINGS_MANAGER_ROLES) return
        val roles = roleRepository.findAccountRoles(pool, teamId, member.accountId)
        if (roles.any { it.permissions[PermissionKeys.MANAGE_TEAM] == true }) return
        if (settingKeys.isNotEmpty() &&
            NICKNAME_FORMAT_KEYS.containsAll(settingKeys) &&
            roles.any { it.permissions[PermissionKeys.MANAGE_NICKNAME_FORMAT] == true }
        ) return
        throw TeamMembershipDeniedException("팀 설정을 관리할 권한이 없습니다.")
    }

    private suspend fun requireActiveMember(teamId: Long, accountId: Long): TeamMemberProjection {
        if (!readiness.isReady) {
            throw TeamMembershipProjectionNotReadyException("team member projection is not ready")
        }
        val member = memberRepository.findForRead(pool, teamId, accountId)
        if (member == null || member.deleted) {
            throw TeamMembershipDeniedException("팀 멤버만 접근할 수 있습니다.")
        }
        return member
    }

    private companion object {
        val SETTINGS_MANAGER_ROLES = setOf("OWNER", "ADMIN")
        val NICKNAME_FORMAT_KEYS = setOf("nickname_format_enforced", "nickname_format_example")
    }
}
