package com.cowork.preference.domain.projectrole.model

import com.cowork.preference.domain.access.permission.PermissionDecision
import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PermissionSource
import com.cowork.preference.domain.access.permission.PermissionSourceKind
import com.cowork.preference.domain.access.permission.PolicyRole
import com.cowork.preference.domain.access.permission.administratorSource
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
    /** 팀 역할 권한 키(`project_` 접두사)별 값. */
    fun toScopedMap(): Map<String, Boolean> = linkedMapOf(
        scopedKey("read") to read,
        scopedKey("write") to write,
        scopedKey("manage") to manage,
        scopedKey("reorder") to reorder,
        scopedKey("channel_archive") to channelArchive,
        scopedKey("board_read") to boardRead,
        scopedKey("board_write") to boardWrite,
    )

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

        fun scopedKey(roleKey: String): String = "project_$roleKey"

        fun resolve(
            teamRoles: List<JsonObject>,
            projectRoles: List<JsonObject>,
            manager: Boolean = false,
            isPrivate: Boolean = false,
        ): ProjectPermissions {
            val decisions = explain(
                teamRoles.map { PolicyRole(null, null, it.booleanValues()) },
                projectRoles.map { PolicyRole(null, null, it.booleanValues()) },
                manager,
                isPrivate,
            )
            fun allowed(roleKey: String) = decisions.getValue(scopedKey(roleKey)).allowed
            return ProjectPermissions(
                allowed("read"),
                allowed("write"),
                allowed("manage"),
                allowed("reorder"),
                allowed("channel_archive"),
                allowed("board_read"),
                allowed("board_write"),
            )
        }

        /**
         * 프로젝트 권한별 판정과 근거를 `project_` 접두사 키로 반환한다.
         * 프로젝트 역할의 명시 값이 팀 역할보다 우선하고, 허용이 거부보다 우선한다.
         * 읽기 이외 권한은 읽기 권한을, 보드 수정은 보드 보기 권한을 함께 요구한다.
         */
        fun explain(
            teamRoles: List<PolicyRole>,
            projectRoles: List<PolicyRole>,
            manager: Boolean,
            isPrivate: Boolean,
        ): Map<String, PermissionDecision> {
            fun sources(roleKey: String): List<PermissionSource> = teamRoles.map {
                PermissionSource(
                    PermissionSourceKind.TEAM_ROLE,
                    scopedKey(roleKey),
                    it.permissions[scopedKey(roleKey)],
                    it.roleId,
                    it.roleName,
                )
            } + projectRoles.map {
                PermissionSource(
                    PermissionSourceKind.PROJECT_ROLE,
                    roleKey,
                    it.permissions[roleKey],
                    it.roleId,
                    it.roleName,
                )
            }
            if (manager) {
                val administratorSources = teamRoles
                    .filter { it.permissions[PermissionKeys.ADMINISTRATOR] == true }
                    .map { it.administratorSource() }
                return ROLE_KEYS.associate {
                    scopedKey(it) to PermissionDecision(
                        true,
                        PermissionDecisionReason.ADMINISTRATOR,
                        sources(it) + administratorSources,
                    )
                }
            }
            fun base(roleKey: String): PermissionDecision {
                val overrides = projectRoles.mapNotNull { it.permissions[roleKey] }
                val (allowed, reason) = when {
                    true in overrides -> true to PermissionDecisionReason.PROJECT_OVERRIDE_ALLOW

                    false in overrides -> false to PermissionDecisionReason.PROJECT_OVERRIDE_DENY

                    teamRoles.any { it.permissions[scopedKey(roleKey)] == true } ->
                        true to PermissionDecisionReason.ROLE_GRANT

                    else -> false to PermissionDecisionReason.NO_GRANT
                }
                return PermissionDecision(allowed, reason, sources(roleKey))
            }
            fun requires(decision: PermissionDecision, prerequisite: Boolean, reason: PermissionDecisionReason) =
                if (decision.allowed && !prerequisite) decision.copy(allowed = false, decidedBy = reason) else decision

            val readBase = base("read")
            val read = if (readBase.allowed && isPrivate && projectRoles.none { it.permissions["read"] == true }) {
                readBase.copy(allowed = false, decidedBy = PermissionDecisionReason.PRIVATE_PROJECT)
            } else {
                readBase
            }
            val boardRead = requires(base("board_read"), read.allowed, PermissionDecisionReason.REQUIRES_PROJECT_READ)
            val boardWrite =
                requires(base("board_write"), boardRead.allowed, PermissionDecisionReason.REQUIRES_BOARD_READ)
            val dependent = listOf("write", "manage", "reorder", "channel_archive").associate {
                scopedKey(it) to requires(base(it), read.allowed, PermissionDecisionReason.REQUIRES_PROJECT_READ)
            }
            val decisions = dependent + mapOf(
                scopedKey("read") to read,
                scopedKey("board_read") to boardRead,
                scopedKey("board_write") to boardWrite,
            )
            return ROLE_KEYS.associate { scopedKey(it) to decisions.getValue(scopedKey(it)) }
        }

        private fun JsonObject.booleanValues(): Map<String, Boolean> =
            map.filterValues { it is Boolean }.mapValues { it.value as Boolean }
    }
}
