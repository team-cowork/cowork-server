package com.cowork.preference.domain.access.model

import com.cowork.preference.domain.channelrole.model.ChannelRolePolicyPermissions
import io.vertx.core.json.JsonObject
import java.time.Instant

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
                    InitialResourceAccessInput.positiveId(overwrite, "roleId"),
                    ChannelRolePolicyPermissions.canonicalize(requireNotNull(overwrite.getJsonObject("permissions"))),
                )
            }
            require(overwrites.isNotEmpty() && overwrites.map { it.roleId }.distinct().size == overwrites.size) {
                "initial overwrites must contain distinct roles"
            }
            val membershipVersion = Instant.parse(payload.getString("actorMembershipVersion"))
            return InitialChannelRolePolicySeed(
                InitialResourceAccessInput.positiveId(payload, "actorId"),
                membershipVersion,
                overwrites,
                InitialResourceAccessInput.authorizationVersion(
                    payload,
                    "actorAuthorizationVersion",
                    membershipVersion,
                ),
            )
        }
    }
}
