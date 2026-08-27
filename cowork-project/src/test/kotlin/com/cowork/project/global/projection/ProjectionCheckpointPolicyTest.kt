package com.cowork.project.global.projection

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ProjectionCheckpointPolicyTest {
    private val stream = ProjectionStream("group", "topic", "cowork-team")
    private val topicId = "topic-id"

    private fun checkpoint(
        nextOffset: Long,
        invalidCheckpointOffset: Long? = null,
        topicId: String = this.topicId,
        snapshotCompletedOffset: Long? = nextOffset - 1,
    ) = ProjectionCheckpoint(nextOffset, topicId, invalidCheckpointOffset, snapshotCompletedOffset)

    private fun processor(
        store: ProjectionCheckpointStore,
        topicGenerations: ProjectionTopicGenerationRegistry = ProjectionTopicGenerationRegistry(),
    ) = ProjectionRecordProcessor(store, ObjectMapper(), topicGenerations)

    private fun barrier(targetOffset: Long, topicId: String = this.topicId) = ProjectionBarrier(topicId, targetOffset)

    @Test
    fun `DB checkpoint가 없으면 broker commit과 무관하게 earliest에서 시작한다`() {
        val decision = ProjectionCatchUpPolicy.seekDecision(null, 3, 10, topicId)
        assertEquals(3, decision.seekOffset)
        assertNull(decision.invalidCheckpointOffset)
    }

    @Test
    fun `topic end보다 큰 checkpoint는 영구 불일치로 표시되어 record를 받아도 ready가 아니다`() {
        val decision = ProjectionCatchUpPolicy.seekDecision(checkpoint(100), 0, 0, topicId)
        assertEquals(0, decision.seekOffset)
        assertEquals(100, decision.invalidCheckpointOffset)
        assertFalse(
            ProjectionCatchUpPolicy.isReady(
                setOf(stream),
                mapOf(stream to mapOf(0 to barrier(1))),
                mapOf(stream to mapOf(0 to checkpoint(1, 100))),
            ),
        )
    }

    @Test
    fun `retention beginning보다 작은 checkpoint도 자동 복구 완료로 간주하지 않는다`() {
        val decision = ProjectionCatchUpPolicy.seekDecision(checkpoint(2), 5, 10, topicId)
        assertEquals(5, decision.seekOffset)
        assertEquals(2, decision.invalidCheckpointOffset)
    }

    @Test
    fun `같은 이름 topic이 재생성되면 offset 범위가 겹쳐도 topic ID 불일치로 ready가 아니다`() {
        val decision = ProjectionCatchUpPolicy.seekDecision(checkpoint(5, topicId = "old-id"), 0, 10, "new-id")
        assertEquals(0, decision.seekOffset)
        assertEquals(5, decision.invalidCheckpointOffset)
        assertFalse(
            ProjectionCatchUpPolicy.isReady(
                setOf(stream),
                mapOf(stream to mapOf(0 to barrier(5, "new-id"))),
                mapOf(stream to mapOf(0 to checkpoint(5, topicId = "old-id"))),
            ),
        )
    }

    @Test
    fun `ready 이후 rebalance 없이 topic이 재생성되어도 broker topic ID 재검증으로 fail closed한다`() {
        val barriers = mapOf(stream to mapOf(0 to barrier(5)))
        val checkpoints = mapOf(stream to mapOf(0 to checkpoint(5)))
        val recreated = mapOf(
            stream.topic to ProjectionTopicState("new-topic-id", mapOf(0 to ProjectionBrokerRange(0, 10))),
        )

        assertTrue(ProjectionCatchUpPolicy.isReady(setOf(stream), barriers, checkpoints))
        assertFalse(ProjectionCatchUpPolicy.matchesBrokerState(setOf(stream), barriers, checkpoints, recreated))
    }

    @Test
    fun `ready 이후 retention beginning이 checkpoint를 지나면 broker 범위 재검증으로 fail closed한다`() {
        val barriers = mapOf(stream to mapOf(0 to barrier(5)))
        val checkpoints = mapOf(stream to mapOf(0 to checkpoint(5)))
        val retained = mapOf(
            stream.topic to ProjectionTopicState(topicId, mapOf(0 to ProjectionBrokerRange(6, 10))),
        )

        assertFalse(ProjectionCatchUpPolicy.matchesBrokerState(setOf(stream), barriers, checkpoints, retained))
    }

    @Test
    fun `모든 required stream partition이 고정 barrier에 도달해야 aggregate ready다`() {
        val other = ProjectionStream("other-group", "other-topic", "other-source")
        val otherTopicId = "other-topic-id"
        val barriers = mapOf(
            stream to mapOf(0 to barrier(5)),
            other to mapOf(0 to barrier(2, otherTopicId), 1 to barrier(4, otherTopicId)),
        )
        val behind = mapOf(
            stream to mapOf(0 to checkpoint(5)),
            other to mapOf(0 to checkpoint(2, topicId = otherTopicId), 1 to checkpoint(3, topicId = otherTopicId)),
        )
        val caughtUp = behind + (
            other to mapOf(0 to checkpoint(2, topicId = otherTopicId), 1 to checkpoint(4, topicId = otherTopicId))
            )
        assertFalse(ProjectionCatchUpPolicy.isReady(setOf(stream, other), barriers, behind))
        assertTrue(ProjectionCatchUpPolicy.isReady(setOf(stream, other), barriers, caughtUp))
    }

    @Test
    fun `local assignment가 없는 replica도 shared checkpoint가 준비되면 ready다`() {
        val store = mockk<ProjectionCheckpointStore>()
        val streams = mockk<ProjectionStreams>()
        every { streams.required } returns setOf(stream)
        every { store.isCaughtUp(setOf(stream)) } returns true
        val state = ProjectionReadinessState(store, streams)
        assertFalse(state.refresh())
        state.markInitialized(stream, locallyAssigned = false)
        assertTrue(state.isReady())
        assertEquals(0, state.assignedCount())
    }

    @Test
    fun `shared barrier가 초기화되기 전에는 ready가 아니다`() {
        assertFalse(ProjectionCatchUpPolicy.isReady(setOf(stream), emptyMap(), emptyMap()))
    }

    @Test
    fun `빈 topic checkpoint는 snapshot completion marker 전까지 ready가 아니다`() {
        val barriers = mapOf(stream to mapOf(0 to barrier(0)))
        val withoutMarker = mapOf(
            stream to mapOf(0 to checkpoint(0, snapshotCompletedOffset = null)),
        )
        val completed = mapOf(
            stream to mapOf(0 to checkpoint(1, snapshotCompletedOffset = 0)),
        )

        assertFalse(ProjectionCatchUpPolicy.isReady(setOf(stream), barriers, withoutMarker))
        assertTrue(ProjectionCatchUpPolicy.isReady(setOf(stream), barriers, completed))
    }

    @Test
    fun `모든 partition이 snapshot completion marker를 소비해야 ready다`() {
        val barriers = mapOf(stream to mapOf(0 to barrier(1), 1 to barrier(1)))
        val checkpoints = mapOf(
            stream to mapOf(
                0 to checkpoint(1, snapshotCompletedOffset = 0),
                1 to checkpoint(1, snapshotCompletedOffset = null),
            ),
        )

        assertFalse(ProjectionCatchUpPolicy.isReady(setOf(stream), barriers, checkpoints))
    }

    @Test
    fun `marker 기반 state stream은 gate하지만 producerless action stream은 required 집합에서 제외한다`() {
        val actionStream = ProjectionStream("action-group", "user.lifecycle", null)
        val barriers = mapOf(stream to mapOf(0 to barrier(1)))
        val checkpoints = mapOf(stream to mapOf(0 to checkpoint(1, snapshotCompletedOffset = 0)))

        assertTrue(ProjectionCatchUpPolicy.isReady(setOf(stream), barriers, checkpoints))
        assertFalse(ProjectionCatchUpPolicy.isReady(setOf(stream, actionStream), barriers, checkpoints))
    }

    @Test
    fun `assignment는 topic 전체 partition barrier를 고정하고 실제 할당 partition만 DB checkpoint로 seek한다`() {
        val consumer = mockk<Consumer<Any, Any>>(relaxed = true)
        val store = mockk<ProjectionCheckpointStore>()
        val state = mockk<ProjectionReadinessState>(relaxed = true)
        val identityProvider = mockk<ProjectionTopicIdentityProvider>()
        val topicGenerations = ProjectionTopicGenerationRegistry()
        val partition0 = TopicPartition(stream.topic, 0)
        val partition1 = TopicPartition(stream.topic, 1)
        every { consumer.partitionsFor(stream.topic) } returns listOf(
            PartitionInfo(stream.topic, 0, null, emptyArray(), emptyArray()),
            PartitionInfo(stream.topic, 1, null, emptyArray(), emptyArray()),
        )
        every { consumer.beginningOffsets(any()) } returns mapOf(partition0 to 2L, partition1 to 4L)
        every { consumer.endOffsets(any()) } returns mapOf(partition0 to 8L, partition1 to 10L)
        every { identityProvider.topicId(stream.topic) } returns topicId
        every { store.initializeAssignment(stream, topicId, any(), setOf(1)) } returns mapOf(1 to 4L)

        ProjectionAssignmentCoordinator(stream, store, state, identityProvider, topicGenerations)
            .onPartitionsAssigned(consumer, listOf(partition1))

        verify {
            store.initializeAssignment(
                stream,
                topicId,
                match { ranges ->
                    ranges.toSet() == setOf(
                        ProjectionPartitionRange(0, 2L, 8L),
                        ProjectionPartitionRange(1, 4L, 10L),
                    )
                },
                setOf(1),
            )
        }
        verify(exactly = 1) { consumer.seek(partition1, 4L) }
        verify(exactly = 0) { consumer.seek(partition0, any<Long>()) }
        assertEquals(topicId, topicGenerations.topicId(stream))
    }

    @Test
    fun `projection apply 실패 시 checkpoint를 진전시키지 않는다`() {
        val store = mockk<ProjectionCheckpointStore>()
        val record = ConsumerRecord("topic", 1, 7L, "key", "payload")
        assertThrows(IllegalStateException::class.java) {
            processor(store).applyRecord(stream, record) {
                throw IllegalStateException("database unavailable")
            }
        }
        verify(exactly = 0) { store.advance(any(), any(), any()) }
    }

    @Test
    fun `invalid record는 격리한 뒤 같은 transaction 경로에서 checkpoint를 진전시킨다`() {
        val store = mockk<ProjectionCheckpointStore>()
        every { store.quarantine(any(), any(), any()) } just runs
        every { store.advance(any(), any(), any()) } just runs
        val record = ConsumerRecord("topic", 1, 7L, "key", "payload")
        processor(store).quarantineRecord(stream, record, "invalid")
        verifyOrder {
            store.quarantine(stream, record, "invalid")
            store.advance(stream, 1, 8L)
        }
    }

    @Test
    fun `유효한 snapshot completion marker는 marker와 next offset을 같은 처리 경로에서 저장한다`() {
        val store = mockk<ProjectionCheckpointStore>()
        val generations = ProjectionTopicGenerationRegistry().apply { markAssigned(stream, topicId) }
        every { store.markSnapshotCompleted(any(), any(), any(), any()) } just runs
        every { store.advance(any(), any(), any()) } just runs
        val record = ConsumerRecord(
            "topic",
            1,
            7L,
            ProjectionSnapshotCompletion.key(1),
            """{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"topic","partition":1,"snapshotId":"00000000-0000-0000-0000-000000000001","occurredAt":"2026-08-26T00:00:00Z","source":"cowork-team"}""",
        )

        assertTrue(processor(store, generations).processControlRecord(stream, record))

        verifyOrder {
            store.markSnapshotCompleted(stream, 1, 7L, topicId)
            store.advance(stream, 1, 8L)
        }
    }

    @Test
    fun `다른 producer의 snapshot completion marker는 readiness 완료로 인정하지 않는다`() {
        val store = mockk<ProjectionCheckpointStore>()
        every { store.quarantine(any(), any(), any()) } just runs
        every { store.advance(any(), any(), any()) } just runs
        val record = ConsumerRecord(
            "topic",
            1,
            7L,
            ProjectionSnapshotCompletion.key(1),
            """{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"topic","partition":1,"snapshotId":"00000000-0000-0000-0000-000000000001","occurredAt":"2026-08-26T00:00:00Z","source":"cowork-deprecated"}""",
        )

        assertTrue(processor(store).processControlRecord(stream, record))

        verifyOrder {
            store.quarantine(stream, record, match { it.contains("cowork-team") })
            store.advance(stream, 1, 8L)
        }
        verify(exactly = 0) { store.markSnapshotCompleted(any(), any(), any(), any()) }
    }

    @Test
    fun `잘못된 reserved marker는 격리하고 checkpoint만 진전시키며 완료로 표시하지 않는다`() {
        val store = mockk<ProjectionCheckpointStore>()
        every { store.quarantine(any(), any(), any()) } just runs
        every { store.advance(any(), any(), any()) } just runs
        val record = ConsumerRecord("topic", 1, 7L, ProjectionSnapshotCompletion.key(0), "{}")

        assertTrue(processor(store).processControlRecord(stream, record))

        verifyOrder {
            store.quarantine(stream, record, any())
            store.advance(stream, 1, 8L)
        }
        verify(exactly = 0) { store.markSnapshotCompleted(any(), any(), any(), any()) }
    }

    @Test
    fun `readiness 전 projection gate는 503으로 fail closed한다`() {
        val state = mockk<ProjectionReadinessState>()
        every { state.isReady() } returns false
        val exception = assertThrows(ExpectedException::class.java) {
            ProjectionReadinessGate(state).requireReady()
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.statusCode)
    }
}
