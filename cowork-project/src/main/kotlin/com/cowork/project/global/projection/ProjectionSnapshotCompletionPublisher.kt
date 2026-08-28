package com.cowork.project.global.projection

import com.cowork.project.global.outbox.OutboxWriter
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ProjectionSnapshotCompletion(
    val eventType: String = EVENT_TYPE,
    val topic: String,
    val partition: Int,
    val snapshotId: String,
    val occurredAt: Instant,
    val source: String,
) {
    companion object {
        const val EVENT_TYPE = "PROJECTION_SNAPSHOT_COMPLETED"
        const val KEY_PREFIX = "__cowork_projection_snapshot_complete__:"

        fun key(partition: Int): String = "$KEY_PREFIX$partition"
    }
}

@Component
class ProjectionSnapshotCompletionPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val outboxWriter: OutboxWriter,
) {
    @Transactional
    fun publishCompleted(topics: Set<String>) {
        require(topics.isNotEmpty()) { "Projection snapshot topics must not be empty" }
        val snapshotId = UUID.randomUUID().toString()
        val occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        topics.sorted().forEach { topic ->
            val partitions = requireNotNull(kafkaTemplate.partitionsFor(topic)) {
                "Kafka topic metadata is unavailable: $topic"
            }.map { it.partition() }.distinct().sorted()
            check(partitions.isNotEmpty()) { "Kafka topic has no partitions: $topic" }
            partitions.forEach { partition ->
                outboxWriter.enqueue(
                    topic = topic,
                    eventKey = ProjectionSnapshotCompletion.key(partition),
                    payload = ProjectionSnapshotCompletion(
                        topic = topic,
                        partition = partition,
                        snapshotId = snapshotId,
                        occurredAt = occurredAt,
                        source = SOURCE,
                    ),
                    partition = partition,
                )
            }
        }
    }

    private companion object {
        const val SOURCE = "cowork-project"
    }
}
