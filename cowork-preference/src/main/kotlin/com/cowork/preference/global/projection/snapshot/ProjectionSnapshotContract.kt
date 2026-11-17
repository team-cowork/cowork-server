package com.cowork.preference.global.projection.snapshot

import com.cowork.preference.global.messaging.event.PreferenceEvent
import io.vertx.core.json.JsonObject
import java.time.Instant

object ProjectionSnapshotContract {
    const val EVENT_TYPE = "PROJECTION_SNAPSHOT_COMPLETED"
    const val KEY_PREFIX = "__cowork_projection_snapshot_complete__:"

    fun create(topic: String, partition: Int, snapshotId: String, occurredAt: Instant): PreferenceEvent {
        require(topic.isNotBlank()) { "topic must not be blank" }
        require(partition >= 0) { "partition must not be negative" }
        return PreferenceEvent(
            topic = topic,
            key = "$KEY_PREFIX$partition",
            payload = JsonObject()
                .put("eventType", EVENT_TYPE)
                .put("topic", topic)
                .put("partition", partition)
                .put("snapshotId", snapshotId)
                .put("occurredAt", occurredAt.toString())
                .put("source", "cowork-preference"),
            occurredAt = occurredAt,
            partition = partition,
        )
    }
}
