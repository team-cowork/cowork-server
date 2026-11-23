package com.cowork.team.global.policy

import com.cowork.team.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@NamedInterface("policy", propagate = false)
class AccountPermissionGuard(
    private val accountPolicyProjection: AccountPolicyProjection,
    private val projectionReadinessGate: ProjectionReadinessGate,
) {
    @Transactional(readOnly = true)
    fun requireCreateTeam(accountId: Long) {
        projectionReadinessGate.requireReady()
        val policies =
            accountPolicyProjection.findAccountPolicies(accountId) + accountPolicyProjection.findTeamPolicies(accountId)
        if (!RolePolicy.allows(policies, PermissionKeys.CREATE_TEAM)) {
            throw ExpectedException("팀을 생성할 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
