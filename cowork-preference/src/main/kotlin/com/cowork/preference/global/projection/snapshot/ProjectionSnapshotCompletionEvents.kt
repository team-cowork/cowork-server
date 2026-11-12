package com.cowork.preference.global.projection.snapshot

import com.cowork.preference.global.messaging.event.PreferenceEvent
import java.time.Instant

object ProjectionSnapshotCompletionEvents {
    fun create(
        topicPartitions: Map<String, Set<Int>>,
        snapshotId: String,
        occurredAt: Instant,
    ): List<PreferenceEvent> = topicPartitions.toSortedMap().flatMap { (topic, partitions) ->
        check(partitions.isNotEmpty()) { "Kafka topic has no partitions: $topic" }
        partitions.sorted().map { partition ->
            ProjectionSnapshotContract.create(topic, partition, snapshotId, occurredAt)
        }
    }
}
