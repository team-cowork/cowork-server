package com.cowork.preference.domain.access.model

import io.vertx.core.json.JsonObject
import java.time.Instant

internal object InitialResourceAccessInput {
    fun authorizationVersion(payload: JsonObject, name: String, membershipVersion: Instant): Instant =
        payload.getValue(name).let { value ->
            require(value is String) { "$name must be an RFC3339 string" }
            Instant.parse(value).also {
                require(it > Instant.EPOCH && it <= membershipVersion) { "$name is outside the membership version" }
            }
        }

    fun positiveId(payload: JsonObject, name: String): Long {
        val value = (payload.getValue(name) as? Number)?.toString()?.toLongOrNull()
        require(value != null && value > 0) { "$name must be a positive integer" }
        return value
    }
}
