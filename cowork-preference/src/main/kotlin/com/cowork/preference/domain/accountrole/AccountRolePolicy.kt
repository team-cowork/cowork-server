package com.cowork.preference.domain.accountrole

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

    fun validate(name: String, permissions: Map<String, Any?>) {
        require(name.isNotBlank() && name.length <= 50) { "역할 이름은 1자 이상 50자 이하여야 합니다." }
        require(permissions.all { (key, value) -> key in actions && value is Boolean }) { "지원하지 않는 계정 역할 권한입니다." }
    }
}
