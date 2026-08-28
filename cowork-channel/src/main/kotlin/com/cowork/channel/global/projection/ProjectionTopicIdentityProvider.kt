package com.cowork.channel.global.projection

import jakarta.annotation.PreDestroy
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class ProjectionTopicIdentityProvider(kafkaProperties: KafkaProperties) {
    private val admin = Admin.create(kafkaProperties.buildAdminProperties())

    fun topicId(topic: String): String {
        val description = requireNotNull(
            admin.describeTopics(listOf(topic)).allTopicNames().get(10, TimeUnit.SECONDS)[topic],
        ) { "Kafka topic metadata가 없습니다: $topic" }
        check(description.topicId() != Uuid.ZERO_UUID) { "Kafka topic ID를 확인할 수 없습니다: $topic" }
        return description.topicId().toString()
    }

    fun topicStates(topics: Set<String>): Map<String, ProjectionTopicState> {
        if (topics.isEmpty()) return emptyMap()
        val descriptions = admin.describeTopics(topics).allTopicNames().get(10, TimeUnit.SECONDS)
        val partitions = descriptions.flatMap { (topic, description) ->
            check(description.topicId() != Uuid.ZERO_UUID) { "Kafka topic ID를 확인할 수 없습니다: $topic" }
            description.partitions().map { TopicPartition(topic, it.partition()) }
        }
        val beginningOffsets = admin.listOffsets(partitions.associateWith { OffsetSpec.earliest() })
            .all().get(10, TimeUnit.SECONDS)
        val endOffsets = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() })
            .all().get(10, TimeUnit.SECONDS)

        return descriptions.mapValues { (topic, description) ->
            ProjectionTopicState(
                topicId = description.topicId().toString(),
                ranges = description.partitions().associate { partitionInfo ->
                    val partition = TopicPartition(topic, partitionInfo.partition())
                    partition.partition() to ProjectionBrokerRange(
                        beginningOffset = requireNotNull(beginningOffsets[partition]).offset(),
                        endOffset = requireNotNull(endOffsets[partition]).offset(),
                    )
                },
            )
        }
    }

    @PreDestroy
    fun close() {
        admin.close(Duration.ofSeconds(5))
    }
}
