package com.cowork.preference.domain.accountrole

import com.cowork.preference.domain.access.permission.PermissionDecision
import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PermissionSource
import com.cowork.preference.domain.access.permission.PermissionSourceKind
import com.cowork.preference.domain.access.permission.PolicyRole

/** Account-wide actions are granted to roles, including the automatically assigned default role. */
object AccountRolePolicy {
    const val CREATE_TEAM = "create_team"
    const val MANAGE_ROLES = "manage_account_roles"
    val operationalActions = setOf(
        "manage_global_roadmaps",
        "read_all_team_roadmaps",
        "create_team_assignments",
        "update_team_assignment_status",
        "delete_team_assignments",
        "manage_chat_search_index",
        "manage_chat_message_quarantine",
        "view_chat_projections",
        "rebuild_chat_projections",
    )

    val actions = operationalActions + setOf(CREATE_TEAM, MANAGE_ROLES)

    fun allows(policies: List<Map<String, Boolean>>, action: String): Boolean =
        action in actions && policies.any { it[action] == true }

    /** [allows]와 같은 규칙으로 판정하고, 판정에 쓰인 계정 역할 값을 함께 반환한다. */
    fun explain(roles: List<PolicyRole>, action: String): PermissionDecision {
        val sources = roles.map {
            PermissionSource(PermissionSourceKind.ACCOUNT_ROLE, action, it.permissions[action], it.roleId, it.roleName)
        }
        return if (allows(roles.map { it.permissions }, action)) {
            PermissionDecision(true, PermissionDecisionReason.ROLE_GRANT, sources)
        } else {
            PermissionDecision(false, PermissionDecisionReason.NO_GRANT, sources)
        }
    }

    fun validate(name: String, permissions: Map<String, Any?>) {
        require(name.isNotBlank() && name.length <= 50) { "역할 이름은 1자 이상 50자 이하여야 합니다." }
        require(permissions.all { (key, value) -> key in actions && value is Boolean }) { "지원하지 않는 계정 역할 권한입니다." }
    }
}
