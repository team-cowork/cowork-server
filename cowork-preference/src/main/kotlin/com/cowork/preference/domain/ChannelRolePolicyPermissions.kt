package com.cowork.preference.domain

import io.vertx.core.json.JsonObject

/**
 * 채널별 역할 권한 덮어쓰기. 키가 있으면 명시적 허용(`true`)·거부(`false`)이고, 키가 없으면 역할 기본 권한을 따른다.
 */
object ChannelRolePolicyPermissions {

    fun canonicalize(permissions: JsonObject): JsonObject {
        require(!permissions.isEmpty) { "permissions must contain at least one key" }
        permissions.fieldNames().forEach { key ->
            require(key in PermissionKeys.CHANNEL_SCOPED) { "permissions.$key is not a channel permission" }
            require(permissions.getValue(key) is Boolean) { "permissions.$key must be boolean" }
        }
        return PermissionKeys.CHANNEL_SCOPED.fold(JsonObject()) { acc, key ->
            if (permissions.containsKey(key)) acc.put(key, permissions.getBoolean(key)) else acc
        }
    }
}
