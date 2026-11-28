package com.cowork.preference.domain.channelrole.model

import com.cowork.preference.domain.access.permission.PermissionDecision
import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PermissionSource
import com.cowork.preference.domain.access.permission.PermissionSourceKind
import com.cowork.preference.domain.access.permission.PolicyRole

object ChannelRolePermissionResolver {
    data class RolePermission(
        val isDefault: Boolean,
        val allowed: Boolean,
        val overwrite: Boolean?,
        val administrator: Boolean = false,
        val roleId: Long? = null,
        val roleName: String? = null,
    )

    /** 인가 판정 경로는 판정 근거를 만들지 않는다. 규칙은 [explain]과 같은 [decide]를 쓴다. */
    fun resolve(roles: List<RolePermission>, permission: String, isPrivate: Boolean): Boolean =
        decide(roles, permission, isPrivate, withSources = false).allowed

    /** 팀 역할 권한에 채널 덮어쓰기를 순서대로 적용한다. 판정 순서는 [PermissionDecisionReason]의 채널 항목과 같다. */
    fun explain(roles: List<RolePermission>, permission: String, isPrivate: Boolean): PermissionDecision =
        decide(roles, permission, isPrivate, withSources = true)

    private fun decide(
        roles: List<RolePermission>,
        permission: String,
        isPrivate: Boolean,
        withSources: Boolean,
    ): PermissionDecision {
        val sources = if (withSources) roles.flatMap { it.sources(permission) } else emptyList()
        val administrators = roles.filter { it.administrator }
        if (permission in PermissionKeys.CHANNEL_SCOPED && administrators.isNotEmpty()) {
            if (!withSources) return PermissionDecision(true, PermissionDecisionReason.ADMINISTRATOR, emptyList())
            val administratorSources = administrators.map {
                PermissionSource(
                    PermissionSourceKind.TEAM_ROLE,
                    PermissionKeys.ADMINISTRATOR,
                    true,
                    it.roleId,
                    it.roleName,
                )
            }
            return PermissionDecision(true, PermissionDecisionReason.ADMINISTRATOR, sources + administratorSources)
        }
        var allowed = roles.any { it.allowed }
        var reason = if (allowed) PermissionDecisionReason.ROLE_GRANT else PermissionDecisionReason.NO_GRANT
        if (isPrivate && permission in PRIVATE_ACCESS_PERMISSIONS) {
            allowed = false
            reason = PermissionDecisionReason.PRIVATE_CHANNEL
        }
        roles.firstOrNull { it.isDefault }?.overwrite?.let {
            allowed = it
            reason = PermissionDecisionReason.DEFAULT_OVERWRITE
        }
        val overrides = roles.filterNot { it.isDefault }.mapNotNull { it.overwrite }
        if (false in overrides) {
            allowed = false
            reason = PermissionDecisionReason.ROLE_OVERWRITE_DENY
        }
        if (true in overrides) {
            allowed = true
            reason = PermissionDecisionReason.ROLE_OVERWRITE_ALLOW
        }
        return PermissionDecision(allowed, reason, sources)
    }

    /** 역할 전체 권한과 채널 덮어쓰기 전체에서 권한 하나의 판정 입력을 만든다. `canAccess` 쿼리와 같은 값을 쓴다. */
    fun rolePermission(role: PolicyRole, overwrite: Map<String, Boolean>?, permission: String) = RolePermission(
        isDefault = role.isDefault,
        allowed = role.permissions[permission] == true,
        overwrite = overwrite?.get(permission),
        administrator = role.permissions[PermissionKeys.ADMINISTRATOR] == true,
        roleId = role.roleId,
        roleName = role.roleName,
    )

    private fun RolePermission.sources(permission: String): List<PermissionSource> {
        val roleSource = PermissionSource(PermissionSourceKind.TEAM_ROLE, permission, allowed, roleId, roleName)
        val overwriteKind = if (isDefault) {
            PermissionSourceKind.CHANNEL_DEFAULT_OVERWRITE
        } else {
            PermissionSourceKind.CHANNEL_ROLE_OVERWRITE
        }
        return listOfNotNull(
            roleSource,
            overwrite?.let { PermissionSource(overwriteKind, permission, it, roleId, roleName) },
        )
    }

    private val PRIVATE_ACCESS_PERMISSIONS = setOf(PermissionKeys.MESSAGE_READ, PermissionKeys.VOICE_CONNECT)
}
