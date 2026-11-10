package com.cowork.preference.domain

import io.vertx.core.json.JsonObject

data class ProjectPermissions(
    val read: Boolean = false,
    val write: Boolean = false,
    val manage: Boolean = false,
    val reorder: Boolean = false,
    val channelArchive: Boolean = false,
) {
    companion object {
        /** 프로젝트 역할에 지정할 수 있는 키. 팀 역할의 기본 키는 각각 `project_` 접두사를 붙인다. */
        val ROLE_KEYS: Set<String> = linkedSetOf("read", "write", "manage", "reorder", "channel_archive")

        fun resolve(
            teamRoles: List<JsonObject>,
            projectRoles: List<JsonObject>,
            manager: Boolean = false,
            isPrivate: Boolean = false,
        ): ProjectPermissions {
            if (manager) return ProjectPermissions(true, true, true, true, true)
            fun allowed(permission: String): Boolean {
                val overrides = projectRoles.mapNotNull { it.getValue(permission) as? Boolean }
                return when {
                    true in overrides -> true
                    false in overrides -> false
                    else -> teamRoles.any { it.getValue("project_$permission") == true }
                }
            }
            val read = allowed("read") && (!isPrivate || projectRoles.any { it.getValue("read") == true })
            return ProjectPermissions(
                read,
                read && allowed("write"),
                read && allowed("manage"),
                read && allowed("reorder"),
                read && allowed("channel_archive"),
            )
        }
    }
}
