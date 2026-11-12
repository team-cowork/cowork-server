package com.cowork.preference.global.projection.snapshot

import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceTopics
import io.vertx.core.json.JsonObject
import java.time.Instant

object ProjectionSnapshotContract {
    const val EVENT_TYPE = "PROJECTION_SNAPSHOT_COMPLETED"
    const val KEY_PREFIX = "__cowork_projection_snapshot_complete__:"

    fun create(topic: String, partition: Int, snapshotId: String, occurredAt: Instant): PreferenceEvent {
        require(
            topic == PreferenceTopics.PROJECT_ACCESS_STATE_TOPIC ||
                topic == PreferenceTopics.CHANNEL_NOTIFICATION_TOPIC ||
                topic == PreferenceTopics.TEAM_ROLE_TOPIC ||
                topic == PreferenceTopics.GITHUB_REPO_SETTING_STATE_TOPIC ||
                topic == PreferenceTopics.CHANNEL_ROLE_POLICY_STATE_TOPIC ||
                topic == PreferenceTopics.ACCOUNT_SETTING_STATE_TOPIC ||
                topic == PreferenceTopics.TEAM_SETTING_STATE_TOPIC ||
                topic == PreferenceTopics.TEXT_CHANNEL_SETTING_STATE_TOPIC ||
                topic == PreferenceTopics.WEBHOOK_STATE_TOPIC ||
                topic == PreferenceTopics.VOICE_CHANNEL_SETTING_STATE_TOPIC,
        ) {
            "unsupported projection snapshot topic '$topic'"
        }
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
