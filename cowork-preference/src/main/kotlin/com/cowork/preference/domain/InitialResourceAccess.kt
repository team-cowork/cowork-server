package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import java.time.Instant

data class InitialChannelRoleOverwrite(val roleId: Long, val permissions: JsonObject)

data class InitialChannelRolePolicySeed(
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val overwrites: List<InitialChannelRoleOverwrite>,
) {
    companion object {
        fun fromJson(payload: JsonObject): InitialChannelRolePolicySeed {
            val overwrites = requireNotNull(payload.getJsonArray("overwrites")).map { value ->
                val overwrite = value as? JsonObject ?: error("initial overwrite must be an object")
                InitialChannelRoleOverwrite(
                    positiveId(overwrite, "roleId"),
                    ChannelRolePolicyPermissions.canonicalize(requireNotNull(overwrite.getJsonObject("permissions"))),
                )
            }
            require(overwrites.isNotEmpty() && overwrites.map { it.roleId }.distinct().size == overwrites.size) {
                "initial overwrites must contain distinct roles"
            }
            return InitialChannelRolePolicySeed(
                positiveId(payload, "actorId"),
                Instant.parse(payload.getString("actorMembershipVersion")),
                overwrites,
            )
        }
    }
}

data class InitialProjectReadGrant(val accountId: Long, val sourceMembershipVersion: Instant) {
    companion object {
        fun fromJson(payload: JsonObject) = InitialProjectReadGrant(
            positiveId(payload, "accountId"),
            Instant.parse(payload.getString("sourceMembershipVersion")),
        )
    }
}

private fun positiveId(payload: JsonObject, name: String): Long {
    val value = (payload.getValue(name) as? Number)?.toString()?.toLongOrNull()
    require(value != null && value > 0) { "$name must be a positive integer" }
    return value
}
