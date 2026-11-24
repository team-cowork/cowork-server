package com.cowork.team.global.policy

import org.springframework.modulith.NamedInterface

/** Role names never grant access. The owner policy alone grants ownership transfer. */
@NamedInterface("policy", propagate = false)
object RolePolicy {
    fun allows(permissions: Map<String, Boolean>, action: String): Boolean = action in PermissionKeys.ROLE_SCOPED &&
        (
            permissions[action] == true ||
                (action != PermissionKeys.TRANSFER_TEAM_OWNERSHIP && permissions[PermissionKeys.ADMINISTRATOR] == true)
            )

    fun allows(policies: Iterable<Map<String, Boolean>>, action: String): Boolean = policies.any { allows(it, action) }

    fun administrator(): Map<String, Boolean> = mapOf(PermissionKeys.ADMINISTRATOR to true)

    fun owner(): Map<String, Boolean> = administrator() + (PermissionKeys.TRANSFER_TEAM_OWNERSHIP to true)
}
