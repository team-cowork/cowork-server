package com.cowork.team.domain.team.service

import com.cowork.team.global.policy.AccountPolicyProjection
import com.cowork.team.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class AccountPermissionGuard(
    private val accounts: AccountPolicyProjection,
    private val readiness: ProjectionReadinessGate,
) {
    fun requireCreateTeam(accountId: Long) {
        readiness.requireReady()
        if (!accounts.allows(accountId, "create_team")) {
            throw ExpectedException("팀을 생성할 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
