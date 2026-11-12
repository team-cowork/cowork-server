package com.cowork.preference.global.messaging.snapshot

import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceEvents
import java.time.Instant

object ProjectionSnapshotCompletionEvents {
    fun create(
        topicPartitions: Map<String, Set<Int>>,
        snapshotId: String,
        occurredAt: Instant,
    ): List<PreferenceEvent> = topicPartitions.toSortedMap().flatMap { (topic, partitions) ->
        check(partitions.isNotEmpty()) { "Kafka topic has no partitions: $topic" }
        partitions.sorted().map { partition ->
            PreferenceEvents.snapshotCompleted(topic, partition, snapshotId, occurredAt)
        }
    }
}
