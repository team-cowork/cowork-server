package com.cowork.preference.domain

import io.vertx.core.json.JsonObject

object ChannelRolePolicyPermissions {

    fun canonicalize(permissions: JsonObject): JsonObject {
        if (
            permissions.containsKey(MESSAGE_READ) &&
            permissions.getValue(MESSAGE_READ) !is Boolean
        ) {
            throw IllegalArgumentException("message_read must be boolean")
        }
        return SettingDefaultsResolver.resolve(permissions, DEFAULTS)
    }

    const val MESSAGE_READ = "message_read"
    private val DEFAULTS: Map<String, Any> = mapOf(MESSAGE_READ to false)
}
