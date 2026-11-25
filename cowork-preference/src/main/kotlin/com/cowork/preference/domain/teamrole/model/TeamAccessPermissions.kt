package com.cowork.preference.domain.teamrole.model

import com.cowork.preference.domain.access.effective.model.EffectivePermissionEvaluator
import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PolicyRole

/** preference.team-access.state.v1로 발행하는 팀 권한 판정. 유효 권한 조회 API와 같은 판정 코드를 쓴다. */
object TeamAccessPermissions {
    private val KEYS = PermissionKeys.TEAM_SCOPED.toList()

    /** [roles]는 기본 역할과 할당 역할이다. 활성 멤버가 아니면 모든 권한을 거부한다. */
    fun resolve(roles: List<PolicyRole>, active: Boolean): Map<String, Boolean> = if (active) {
        EffectivePermissionEvaluator.team(roles, KEYS).mapValues { it.value.allowed }
    } else {
        KEYS.associateWith { false }
    }
}
