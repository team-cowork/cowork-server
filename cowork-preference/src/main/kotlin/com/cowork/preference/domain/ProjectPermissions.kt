package com.cowork.preference.domain

import io.vertx.core.json.JsonObject

data class ProjectPermissions(
    val read: Boolean = false,
    val write: Boolean = false,
    val manage: Boolean = false,
    val reorder: Boolean = false,
) {
    companion object {
        fun resolve(teamRoles: List<JsonObject>, projectRoles: List<JsonObject>, manager: Boolean = false, isPrivate: Boolean = false): ProjectPermissions {
            if (manager) return ProjectPermissions(true, true, true, true)
            fun allowed(permission: String): Boolean {
                val overrides = projectRoles.mapNotNull { it.getValue(permission) as? Boolean }
                return when {
                    true in overrides -> true
                    false in overrides -> false
                    else -> teamRoles.any { it.getValue("project_$permission") == true }
                }
            }
            val read = allowed("read") && (!isPrivate || projectRoles.any { it.getValue("read") == true })
            return ProjectPermissions(read, read && allowed("write"), read && allowed("manage"), read && allowed("reorder"))
        }
    }
}
