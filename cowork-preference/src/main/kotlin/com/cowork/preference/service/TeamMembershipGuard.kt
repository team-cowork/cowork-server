package com.cowork.preference.service

import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.security.RequesterContext
import io.vertx.sqlclient.Pool

/** 팀 멤버 projection이 아직 해당 팀/계정 상태를 따라잡지 못했을 때 던진다. 호출부는 503으로 매핑해야 한다. */
class TeamMembershipProjectionNotReadyException(message: String) : IllegalStateException(message)

/** 요청자가 팀 멤버가 아니거나 요구되는 관리 권한이 없을 때 던진다. 호출부는 403으로 매핑해야 한다. */
class TeamMembershipDeniedException(message: String) : RuntimeException(message)

/**
 * 팀 범위 Preference API 호출자가 대상 팀에 접근할 권한이 있는지 [TeamMemberProjectionRepository] projection으로 검증한다.
 * 전역 `X-User-Role: ADMIN`은 팀 멤버십 검증을 우회하는 예외 경로로 취급한다.
 */
class TeamMembershipGuard(
    private val pool: Pool,
    private val memberRepository: TeamMemberProjectionRepository,
    private val readiness: ProjectionReadinessView,
) {
    /** 팀 조회 최소 권한: 팀 멤버(어떤 역할이든) 또는 전역 ADMIN. */
    suspend fun requireMember(teamId: Long, requester: RequesterContext) {
        if (requester.isGlobalAdmin) return
        requireActiveMember(teamId, requester.userId)
    }

    /** 팀 설정 수정 최소 권한: 팀 OWNER/ADMIN 또는 전역 ADMIN. */
    suspend fun requireSettingsManager(teamId: Long, requester: RequesterContext) {
        if (requester.isGlobalAdmin) return
        val member = requireActiveMember(teamId, requester.userId)
        if (member.builtInRole !in SETTINGS_MANAGER_ROLES) {
            throw TeamMembershipDeniedException("팀 설정을 관리할 권한이 없습니다.")
        }
    }

    private suspend fun requireActiveMember(teamId: Long, accountId: Long): TeamMemberProjection {
        if (!readiness.isReady) {
            throw TeamMembershipProjectionNotReadyException("team member projection is not ready")
        }
        val member = memberRepository.find(pool, teamId, accountId)
        if (member == null || member.deleted) {
            throw TeamMembershipDeniedException("팀 멤버만 접근할 수 있습니다.")
        }
        return member
    }

    private companion object {
        val SETTINGS_MANAGER_ROLES = setOf("OWNER", "ADMIN")
    }
}
