package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import java.time.Instant
import java.util.UUID

object ProjectionSnapshotCompletion {
    private const val EXPECTED_SOURCE = "cowork-team"

    fun isReserved(key: String?): Boolean = key?.startsWith(PreferenceEvents.SNAPSHOT_COMPLETED_KEY_PREFIX) == true

    fun violation(key: String?, payloadValue: String?, topic: String, partition: Int): String? {
        if (key != "${PreferenceEvents.SNAPSHOT_COMPLETED_KEY_PREFIX}$partition") {
            return "projection snapshot marker key does not match its partition"
        }
        val payload = runCatching { JsonObject(payloadValue) }
            .getOrElse { return "projection snapshot marker JSON is invalid: ${it.message}" }
        if (payload.getString("eventType") != PreferenceEvents.SNAPSHOT_COMPLETED_EVENT_TYPE) {
            return "projection snapshot marker eventType is invalid"
        }
        if (payload.getString("topic") != topic) return "projection snapshot marker topic does not match the record"
        if (payload.getInteger("partition") != partition) {
            return "projection snapshot marker partition does not match the record"
        }
        if (runCatching { UUID.fromString(payload.getString("snapshotId")) }.isFailure) {
            return "projection snapshot marker snapshotId is invalid"
        }
        if (runCatching { Instant.parse(payload.getString("occurredAt")) }.isFailure) {
            return "projection snapshot marker occurredAt is invalid"
        }
        if (payload.getString("source") != EXPECTED_SOURCE) {
            return "projection snapshot marker source does not match the expected producer: $EXPECTED_SOURCE"
        }
        return null
    }
}
