package com.cowork.preference.domain.projectrole.model

import io.vertx.core.json.JsonObject

data class ProjectPermissions(
    val read: Boolean = false,
    val write: Boolean = false,
    val manage: Boolean = false,
    val reorder: Boolean = false,
    val channelArchive: Boolean = false,
    val boardRead: Boolean = false,
    val boardWrite: Boolean = false,
) {
    companion object {
        /** 프로젝트 역할에 지정할 수 있는 키. 팀 역할의 기본 키는 각각 `project_` 접두사를 붙인다. */
        val ROLE_KEYS: Set<String> = linkedSetOf(
            "read",
            "write",
            "manage",
            "reorder",
            "channel_archive",
            "board_read",
            "board_write",
        )

        fun resolve(
            teamRoles: List<JsonObject>,
            projectRoles: List<JsonObject>,
            manager: Boolean = false,
            isPrivate: Boolean = false,
        ): ProjectPermissions {
            if (manager) {
                return ProjectPermissions(
                    read = true,
                    write = true,
                    manage = true,
                    reorder = true,
                    channelArchive = true,
                    boardRead = true,
                    boardWrite = true,
                )
            }
            fun allowed(permission: String): Boolean {
                val overrides = projectRoles.mapNotNull { it.getValue(permission) as? Boolean }
                return when {
                    true in overrides -> true
                    false in overrides -> false
                    else -> teamRoles.any { it.getValue("project_$permission") == true }
                }
            }
            val read = allowed("read") && (!isPrivate || projectRoles.any { it.getValue("read") == true })
            // 보드 수정은 보드를 볼 수 있을 때만 의미가 있으므로 보드 보기에 종속시킨다.
            val boardRead = read && allowed("board_read")
            return ProjectPermissions(
                read,
                read && allowed("write"),
                read && allowed("manage"),
                read && allowed("reorder"),
                read && allowed("channel_archive"),
                boardRead,
                boardRead && allowed("board_write"),
            )
        }
    }
}
