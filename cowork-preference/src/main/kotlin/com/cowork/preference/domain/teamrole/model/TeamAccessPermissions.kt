package com.cowork.preference.domain.teamrole.model

import com.cowork.preference.domain.access.effective.model.EffectivePermissionEvaluator
import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PolicyRole
import com.cowork.preference.domain.access.permission.RolePolicy

/** preference.team-access.state.v2로 발행하는 팀 권한 판정. 유효 권한 조회 API와 같은 판정 코드를 쓴다. */
object TeamAccessPermissions {
    private val KEYS = PermissionKeys.TEAM_SCOPED.toList()
    private val BASE_KEYS = (PermissionKeys.CHANNEL_SCOPED + PermissionKeys.PROJECT_SCOPED).sorted()

    /** [roles]는 기본 역할과 할당 역할이다. 활성 멤버가 아니면 모든 권한을 거부한다. */
    fun resolve(roles: List<PolicyRole>, active: Boolean): Map<String, Boolean> = if (active) {
        EffectivePermissionEvaluator.team(roles, KEYS).mapValues { it.value.allowed }
    } else {
        KEYS.associateWith { false }
    }

    /** 역할 부여와 리소스 생성에 쓰는 팀 역할의 기본 권한. 개별 채널과 프로젝트의 덮어쓰기는 그 리소스에서 적용한다. */
    fun baseGrants(roles: List<PolicyRole>, active: Boolean): List<String> = if (active) {
        BASE_KEYS.filter { key -> roles.any { RolePolicy.allows(it.permissions, key) } }
    } else {
        emptyList()
    }
}
