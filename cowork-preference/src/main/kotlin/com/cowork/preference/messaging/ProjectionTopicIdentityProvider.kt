package com.cowork.preference.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid
import java.time.Duration
import java.util.concurrent.TimeUnit

data class ProjectionBrokerRange(val beginningOffset: Long, val endOffset: Long)

data class ProjectionTopicState(val topicId: String, val ranges: Map<Int, ProjectionBrokerRange>)

class ProjectionTopicIdentityProvider(bootstrapServers: String) : AutoCloseable {
    private val admin = Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers))

    suspend fun topicState(topic: String): ProjectionTopicState = withContext(Dispatchers.IO) {
        val description = requireNotNull(
            admin.describeTopics(listOf(topic)).allTopicNames().get(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)[topic],
        ) { "Kafka topic metadata is unavailable: $topic" }
        check(description.topicId() != Uuid.ZERO_UUID) { "Kafka topic ID is unavailable: $topic" }
        val partitions = description.partitions().map { TopicPartition(topic, it.partition()) }
        check(partitions.isNotEmpty()) { "Kafka topic has no partitions: $topic" }
        val beginnings = admin.listOffsets(partitions.associateWith { OffsetSpec.earliest() })
            .all().get(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val ends = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() })
            .all().get(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        ProjectionTopicState(
            topicId = description.topicId().toString(),
            ranges = partitions.associate { partition ->
                partition.partition() to ProjectionBrokerRange(
                    beginningOffset = requireNotNull(beginnings[partition]).offset(),
                    endOffset = requireNotNull(ends[partition]).offset(),
                )
            },
        )
    }

    override fun close() {
        admin.close(Duration.ofSeconds(5))
    }

    private companion object {
        const val METADATA_TIMEOUT_SECONDS = 10L
    }
}
