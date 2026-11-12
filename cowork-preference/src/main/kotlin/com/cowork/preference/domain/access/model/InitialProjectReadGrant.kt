package com.cowork.preference.domain.access.model

import io.vertx.core.json.JsonObject
import java.time.Instant

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
