package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import java.time.Instant

data class InitialChannelRoleOverwrite(val roleId: Long, val permissions: JsonObject)

data class InitialChannelRolePolicySeed(
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val overwrites: List<InitialChannelRoleOverwrite>,
    val actorAuthorizationVersion: Instant? = null,
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
    val sourceAuthorizationVersion: Instant? = null,
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

// TODO(#596): 2026-11-01 05:00 KST 이후 구형 producer/재처리 종료, legacy seed 원장 처리와 snapshot 확인 후
// NULL seed는 재승인 없이 건너뛰도록 호환 경로 제거. 불변 seed가 snapshot에 남아 있으므로 필수화로 lifecycle을 격리하지 않는다.
// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 배포 지연 시 제거일도 연기한다.
private fun authorizationVersion(payload: JsonObject, name: String, membershipVersion: Instant): Instant? =
    payload.getValue(name)?.let { value ->
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
