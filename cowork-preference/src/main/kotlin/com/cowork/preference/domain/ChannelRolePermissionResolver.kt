package com.cowork.preference.domain

object ChannelRolePermissionResolver {
    data class RolePermission(val isDefault: Boolean, val allowed: Boolean, val overwrite: Boolean?)

    fun resolve(roles: List<RolePermission>, permission: String, isPrivate: Boolean): Boolean {
        var allowed = roles.any { it.allowed }
        if (isPrivate && permission in setOf(PermissionKeys.MESSAGE_READ, PermissionKeys.VOICE_CONNECT)) allowed = false
        roles.firstOrNull { it.isDefault }?.overwrite?.let { allowed = it }
        val overrides = roles.filterNot { it.isDefault }.mapNotNull { it.overwrite }
        if (false in overrides) allowed = false
        if (true in overrides) allowed = true
        return allowed
    }
}
