package com.cowork.preference.domain

import io.vertx.core.json.JsonObject

/**
 * 팀 역할의 기본 권한. 모든 키를 boolean으로 채운 전체 상태이며 누락된 키는 `false`다.
 */
object TeamRolePermissions {

    fun validate(permissions: Map<String, Any?>) {
        permissions.forEach { (key, value) ->
            require(key in PermissionKeys.ROLE_SCOPED) { "role.permissions.$key is not a role permission" }
            require(value is Boolean) { "role.permissions.$key must be boolean" }
        }
    }

    fun canonicalize(permissions: Map<String, Boolean>): Map<String, Boolean> =
        PermissionKeys.ROLE_SCOPED.associateWith { permissions[it] ?: false }

    fun merge(current: Map<String, Boolean>, changes: Map<String, Boolean>): Map<String, Boolean> =
        canonicalize(current + changes)

    fun fromStored(json: JsonObject?): Map<String, Boolean> =
        PermissionKeys.ROLE_SCOPED.associateWith { json?.getValue(it) as? Boolean ?: false }

    fun toJson(permissions: Map<String, Boolean>): JsonObject =
        canonicalize(permissions).entries.fold(JsonObject()) { acc, (key, value) -> acc.put(key, value) }
}
