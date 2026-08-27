package com.cowork.channel.global.projection

import com.cowork.channel.global.outbox.OutboxWriter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.common.PartitionInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate

class ProjectionSnapshotCompletionPublisherTest {
    @Test
    fun `모든 topic partition에 같은 snapshot generation marker를 명시적으로 enqueue한다`() {
        val kafkaTemplate = mockk<KafkaTemplate<String, Any>>()
        val outboxWriter = mockk<OutboxWriter>(relaxed = true)
        every { kafkaTemplate.partitionsFor("channel.event") } returns partitions("channel.event", 0, 1)
        every { kafkaTemplate.partitionsFor("channel.member.event") } returns partitions("channel.member.event", 0)
        val publisher = ProjectionSnapshotCompletionPublisher(kafkaTemplate, outboxWriter)
        val payloads = mutableListOf<Any>()

        publisher.publishCompleted(setOf("channel.event", "channel.member.event"))

        verify(exactly = 3) { outboxWriter.enqueue(any(), any(), capture(payloads), any()) }
        val markers = payloads.map { it as ProjectionSnapshotCompletion }
        assertEquals(1, markers.map { it.snapshotId }.toSet().size)
        assertEquals(1, markers.map { it.occurredAt }.toSet().size)
        assertEquals(
            setOf("channel.event:0", "channel.event:1", "channel.member.event:0"),
            markers.map { "${it.topic}:${it.partition}" }.toSet(),
        )
    }

    private fun partitions(topic: String, vararg ids: Int) = ids.map { id ->
        PartitionInfo(topic, id, null, emptyArray(), emptyArray())
    }
}
