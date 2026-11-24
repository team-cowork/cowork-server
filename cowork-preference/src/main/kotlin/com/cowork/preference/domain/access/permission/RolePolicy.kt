package com.cowork.preference.domain.access.permission

/** Role names never grant access. The owner policy alone grants ownership transfer. */
object RolePolicy {
    fun allows(permissions: Map<String, Boolean>, action: String): Boolean =
        grants(permissions, action) || bypasses(permissions, action)

    fun allows(policies: Iterable<Map<String, Boolean>>, action: String): Boolean = policies.any { allows(it, action) }

    /** [allows]와 같은 규칙으로 판정하고, 판정에 쓰인 역할 값을 함께 반환한다. */
    fun explain(roles: List<PolicyRole>, action: String): PermissionDecision {
        val sources = roles.map {
            PermissionSource(
                PermissionSourceKind.TEAM_ROLE,
                action,
                it.permissions[action],
                it.roleId,
                it.roleName,
                it.teamId,
            )
        }
        if (roles.any { grants(it.permissions, action) }) {
            return PermissionDecision(true, PermissionDecisionReason.ROLE_GRANT, sources)
        }
        val administrators = roles.filter { bypasses(it.permissions, action) }
        if (administrators.isNotEmpty()) {
            return PermissionDecision(
                true,
                PermissionDecisionReason.ADMINISTRATOR,
                sources + administrators.map { it.administratorSource() },
            )
        }
        return PermissionDecision(false, PermissionDecisionReason.NO_GRANT, sources)
    }

    fun administrator(): Map<String, Boolean> = mapOf(PermissionKeys.ADMINISTRATOR to true)

    fun owner(): Map<String, Boolean> = administrator() + (PermissionKeys.TRANSFER_TEAM_OWNERSHIP to true)

    private fun grants(permissions: Map<String, Boolean>, action: String): Boolean =
        action in PermissionKeys.ROLE_SCOPED && permissions[action] == true

    private fun bypasses(permissions: Map<String, Boolean>, action: String): Boolean =
        action in PermissionKeys.ROLE_SCOPED &&
            action != PermissionKeys.TRANSFER_TEAM_OWNERSHIP &&
            permissions[PermissionKeys.ADMINISTRATOR] == true
}

internal fun PolicyRole.administratorSource(): PermissionSource = PermissionSource(
    PermissionSourceKind.TEAM_ROLE,
    PermissionKeys.ADMINISTRATOR,
    true,
    roleId,
    roleName,
    teamId,
)
