package com.cowork.team.domain.team.service

import com.cowork.team.global.policy.AccountPolicyProjection
import com.cowork.team.global.policy.PermissionKeys
import com.cowork.team.global.policy.RolePolicy
import com.cowork.team.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class AccountPermissionGuard(
    private val accounts: AccountPolicyProjection,
    private val readiness: ProjectionReadinessGate,
) {
    @Transactional(readOnly = true)
    fun requireCreateTeam(accountId: Long) {
        readiness.requireReady()
        val policies = accounts.findAccountPolicies(accountId) + accounts.findTeamPolicies(accountId)
        if (!RolePolicy.allows(policies, PermissionKeys.CREATE_TEAM)) {
            throw ExpectedException("팀을 생성할 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
