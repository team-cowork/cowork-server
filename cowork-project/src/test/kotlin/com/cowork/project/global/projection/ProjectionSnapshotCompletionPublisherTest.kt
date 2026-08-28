package com.cowork.project.global.projection

import com.cowork.project.global.outbox.OutboxWriter
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
        every { kafkaTemplate.partitionsFor("project.event") } returns partitions("project.event", 0, 1)
        every { kafkaTemplate.partitionsFor("project.member.event") } returns partitions("project.member.event", 0)
        val publisher = ProjectionSnapshotCompletionPublisher(kafkaTemplate, outboxWriter)
        val payloads = mutableListOf<Any>()

        publisher.publishCompleted(setOf("project.event", "project.member.event"))

        verify(exactly = 3) { outboxWriter.enqueue(any(), any(), capture(payloads), any()) }
        val markers = payloads.map { it as ProjectionSnapshotCompletion }
        assertEquals(1, markers.map { it.snapshotId }.toSet().size)
        assertEquals(1, markers.map { it.occurredAt }.toSet().size)
        assertEquals(
            setOf("project.event:0", "project.event:1", "project.member.event:0"),
            markers.map { "${it.topic}:${it.partition}" }.toSet(),
        )
    }

    private fun partitions(topic: String, vararg ids: Int) = ids.map { id ->
        PartitionInfo(topic, id, null, emptyArray(), emptyArray())
    }
}
