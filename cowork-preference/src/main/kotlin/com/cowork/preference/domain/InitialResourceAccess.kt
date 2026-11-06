package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import java.time.Instant

data class InitialChannelRoleOverwrite(val roleId: Long, val permissions: JsonObject)

data class InitialChannelRolePolicySeed(
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val overwrites: List<InitialChannelRoleOverwrite>,
    val actorAuthorizationVersion: Instant,
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
            val membershipVersion = Instant.parse(payload.getString("actorMembershipVersion"))
            return InitialChannelRolePolicySeed(
                positiveId(payload, "actorId"),
                membershipVersion,
                overwrites,
                authorizationVersion(payload, "actorAuthorizationVersion", membershipVersion),
            )
        }
    }
}

data class InitialProjectReadGrant(
    val accountId: Long,
    val sourceMembershipVersion: Instant,
    val sourceAuthorizationVersion: Instant,
) {
    companion object {
        fun fromJson(payload: JsonObject): InitialProjectReadGrant {
            val membershipVersion = Instant.parse(payload.getString("sourceMembershipVersion"))
            return InitialProjectReadGrant(
                positiveId(payload, "accountId"),
                membershipVersion,
                authorizationVersion(payload, "sourceAuthorizationVersion", membershipVersion),
            )
        }
    }
}

private fun authorizationVersion(payload: JsonObject, name: String, membershipVersion: Instant): Instant =
    payload.getValue(name).let { value ->
        require(value is String) { "$name must be an RFC3339 string" }
        Instant.parse(value).also {
            require(it > Instant.EPOCH && it <= membershipVersion) { "$name is outside the membership version" }
        }
    }

private fun positiveId(payload: JsonObject, name: String): Long {
    val value = (payload.getValue(name) as? Number)?.toString()?.toLongOrNull()
    require(value != null && value > 0) { "$name must be a positive integer" }
    return value
}
