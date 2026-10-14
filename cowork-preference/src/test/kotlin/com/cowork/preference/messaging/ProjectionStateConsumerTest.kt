package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectionAssignmentResult
import com.cowork.preference.repository.ProjectionBarrier
import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.kafka.client.common.TopicPartition
import io.vertx.kafka.client.consumer.KafkaConsumer
import io.vertx.kafka.client.consumer.KafkaConsumerRecord
import io.vertx.kafka.client.consumer.KafkaConsumerRecords
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList

class ProjectionStateConsumerTest :
    DescribeSpec({
        lateinit var fixture: ProjectionConsumerFixture

        beforeEach {
            fixture = ProjectionConsumerFixture()
            fixture.start()
        }

        afterEach {
            fixture.close()
        }

        describe("ProjectionStateConsumer 클래스의") {
            context("체크포인트 초기화와 seek가 완료되지 않은 경우") {
                it("미리 받은 배치를 버리고 seek 이후 다시 받은 레코드만 반영한다") {
                    // Given
                    val initialization = CompletableDeferred<ProjectionAssignmentResult>()
                    val seek = Promise.promise<Void>()
                    coEvery { fixture.repository.initializeAssignment(any(), any(), any(), any(), any()) } coAnswers {
                        initialization.await()
                    }
                    every { fixture.consumer.seek(fixture.partition, 10L) } returns seek.future()

                    // When
                    fixture.assign()
                    fixture.deliver(offset = 20L)

                    // Then
                    fixture.errors.toList() shouldBe emptyList()
                    coVerify(exactly = 0) { fixture.handler.apply(any(), any(), any()) }
                    verify(exactly = 0) { fixture.consumer.resume() }

                    // When
                    initialization.complete(ProjectionAssignmentResult(mapOf(0 to 10L), false))
                    fixture.drain()
                    fixture.deliver(offset = 20L)

                    // Then
                    coVerify(exactly = 0) { fixture.handler.apply(any(), any(), any()) }
                    verify(exactly = 0) { fixture.consumer.resume() }

                    // When
                    seek.complete()
                    fixture.awaitResume()
                    fixture.deliver(offset = 10L)

                    // Then
                    fixture.errors.toList() shouldBe emptyList()
                    coVerify(exactly = 1) {
                        fixture.handler.apply(fixture.checkpoint.copy(nextOffset = 11L), "state-key", "state-value")
                    }
                    coVerify(exactly = 0) {
                        fixture.handler.apply(match { it.nextOffset == 21L }, any(), any())
                    }
                    verify(exactly = 1) { fixture.consumer.resume(setOf(fixture.partition)) }
                    verify(exactly = 2) { fixture.consumer.resume() }
                }
            }

            context("이전 할당의 초기화 도중 재할당된 경우") {
                it("이전 저장소 응답은 seek하지 않고 새 할당이 초기화된 뒤 소비를 재개한다") {
                    // Given
                    val previous = CompletableDeferred<ProjectionAssignmentResult>()
                    val current = CompletableDeferred<ProjectionAssignmentResult>()
                    coEvery { fixture.repository.initializeAssignment(any(), any(), any(), any(), any()) } coAnswers {
                        previous.await()
                    } coAndThen {
                        current.await()
                    }

                    // When
                    fixture.assign()
                    fixture.revoke()
                    fixture.assign()
                    previous.complete(ProjectionAssignmentResult(mapOf(0 to 20L), false))
                    fixture.drain()
                    fixture.deliver(offset = 20L)

                    // Then
                    fixture.readiness.snapshot().initialized shouldBe false
                    verify(exactly = 0) { fixture.consumer.seek(any<TopicPartition>(), any<Long>()) }
                    verify(exactly = 1) { fixture.consumer.resume() }
                    coVerify(exactly = 0) { fixture.handler.apply(any(), any(), any()) }

                    // When
                    current.complete(ProjectionAssignmentResult(mapOf(0 to 10L), false))
                    fixture.drain()
                    fixture.deliver(offset = 10L)

                    // Then
                    fixture.errors.toList() shouldBe emptyList()
                    fixture.readiness.snapshot().initialized shouldBe true
                    coVerify(exactly = 1) {
                        fixture.handler.apply(fixture.checkpoint.copy(nextOffset = 11L), any(), any())
                    }
                    verify(exactly = 3) { fixture.consumer.resume() }
                }

                it("이전 seek가 늦게 끝나도 새 할당의 초기화 상태와 소비 중단을 유지한다") {
                    // Given
                    val previousSeek = Promise.promise<Void>()
                    val current = CompletableDeferred<ProjectionAssignmentResult>()
                    every { fixture.consumer.seek(fixture.partition, 10L) } returns previousSeek.future()
                    fixture.assign()
                    coEvery { fixture.repository.initializeAssignment(any(), any(), any(), any(), any()) } coAnswers {
                        current.await()
                    }

                    // When
                    fixture.revoke()
                    fixture.assign()
                    previousSeek.complete()
                    fixture.drain()

                    // Then
                    fixture.errors.toList() shouldBe emptyList()
                    fixture.readiness.snapshot().initialized shouldBe false
                    verify(exactly = 0) { fixture.consumer.resume(setOf(fixture.partition)) }
                    verify(exactly = 1) { fixture.consumer.resume() }
                }
            }

            context("파티션 소비 재개가 완료되지 않은 경우") {
                it("스트림 수요와 레코드 적용을 재개하지 않는다") {
                    // Given
                    val resume = Promise.promise<Void>()
                    every { fixture.consumer.resume(setOf(fixture.partition)) } returns resume.future()

                    // When
                    fixture.assign()
                    fixture.deliver(offset = 10L)

                    // Then
                    verify(exactly = 0) { fixture.consumer.resume() }
                    coVerify(exactly = 0) { fixture.handler.apply(any(), any(), any()) }

                    // When
                    resume.complete()
                    fixture.awaitResume()
                    fixture.deliver(offset = 10L)

                    // Then
                    coVerify(exactly = 1) {
                        fixture.handler.apply(fixture.checkpoint.copy(nextOffset = 11L), any(), any())
                    }
                }
            }

            context("체크포인트 연속성을 증명할 수 없는 경우") {
                it("초기화 결과의 범위 오류가 있으면 소비를 재개하지 않는다") {
                    // Given
                    coEvery { fixture.repository.initializeAssignment(any(), any(), any(), any(), any()) } returns
                        ProjectionAssignmentResult(mapOf(0 to 10L), true)

                    // When
                    fixture.assign()
                    fixture.deliver(offset = 10L)

                    // Then
                    fixture.readiness.isReady shouldBe false
                    verify(exactly = 0) { fixture.consumer.resume(setOf(fixture.partition)) }
                    verify(exactly = 0) { fixture.consumer.resume() }
                    coVerify(exactly = 0) { fixture.handler.apply(any(), any(), any()) }
                }

                it("저장된 무효 레코드 표시가 있으면 초기화 후에도 적용하지 않는다") {
                    // Given
                    coEvery { fixture.repository.load(any(), any()) } returns
                        mapOf(0 to fixture.checkpoint.copy(invalidRecordOffset = 9L))

                    // When
                    fixture.assign()
                    fixture.deliver(offset = 10L)

                    // Then
                    fixture.readiness.isReady shouldBe false
                    verify(exactly = 0) { fixture.consumer.resume() }
                    coVerify(exactly = 0) { fixture.handler.apply(any(), any(), any()) }
                }
            }
        }
    })

private class ProjectionConsumerFixture {
    private val vertx = Vertx.vertx()
    private val job = SupervisorJob()
    val errors = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(job + CoroutineExceptionHandler { _, error -> errors.add(error) })
    val consumer = mockk<KafkaConsumer<String, String>>()
    val repository = mockk<ProjectionCheckpointRepository>()
    val handler = mockk<ProjectionRecordHandler>()
    val readiness = ProjectionReadiness()
    val partition = TopicPartition(TOPIC, 0)
    val checkpoint = ProjectionCheckpoint(GROUP, TOPIC, 0, 10L, "topic-id")
    private val topicIdentity = mockk<ProjectionTopicIdentityProvider>()
    private val assigned = slot<Handler<Set<TopicPartition>>>()
    private val revoked = slot<Handler<Set<TopicPartition>>>()
    private val batch = slot<Handler<KafkaConsumerRecords<String, String>>>()
    private val resumed = CompletableDeferred<Unit>()
    private val projection: ProjectionStateConsumer

    init {
        mockkStatic(KafkaConsumer::class)
        every { KafkaConsumer.create<String, String>(vertx, any<Map<String, String>>()) } returns consumer
        every { consumer.exceptionHandler(any()) } returns consumer
        every { consumer.partitionsAssignedHandler(capture(assigned)) } returns consumer
        every { consumer.partitionsRevokedHandler(capture(revoked)) } returns consumer
        every { consumer.batchHandler(capture(batch)) } returns consumer
        every { consumer.subscribe(TOPIC) } returns Future.succeededFuture()
        every { consumer.pause() } returns consumer
        every { consumer.pause(any<Set<TopicPartition>>()) } returns Future.succeededFuture()
        every { consumer.resume(any<Set<TopicPartition>>()) } returns Future.succeededFuture()
        every { consumer.resume() } answers {
            resumed.complete(Unit)
            consumer
        }
        every { consumer.seek(any<TopicPartition>(), any<Long>()) } returns Future.succeededFuture()
        every { consumer.assignment() } returns Future.succeededFuture(setOf(partition))
        every { consumer.close() } returns Future.succeededFuture()
        coEvery { topicIdentity.topicState(TOPIC) } returns
            ProjectionTopicState("topic-id", mapOf(0 to ProjectionBrokerRange(0L, 30L)))
        coEvery { repository.initializeAssignment(any(), any(), any(), any(), any()) } returns
            ProjectionAssignmentResult(mapOf(0 to 10L), false)
        coEvery { repository.load(GROUP, TOPIC) } returns mapOf(0 to checkpoint)
        coEvery { repository.loadBarriers(GROUP, TOPIC) } returns mapOf(0 to ProjectionBarrier("topic-id", 30L))
        coEvery { handler.apply(any(), any(), any()) } returns null
        projection = ProjectionStateConsumer(
            vertx,
            "unused:9092",
            GROUP,
            TOPIC,
            "test-source",
            handler,
            repository,
            topicIdentity,
            readiness,
            scope,
        )
    }

    suspend fun start() = withContext(vertx.dispatcher()) {
        projection.start()
    }

    suspend fun assign() = withContext(vertx.dispatcher()) {
        assigned.captured.handle(setOf(partition))
        yield()
    }

    suspend fun revoke() = withContext(vertx.dispatcher()) {
        revoked.captured.handle(setOf(partition))
        yield()
    }

    suspend fun deliver(offset: Long) = withContext(vertx.dispatcher()) {
        val record = mockk<KafkaConsumerRecord<String, String>>()
        every { record.topic() } returns TOPIC
        every { record.partition() } returns 0
        every { record.offset() } returns offset
        every { record.key() } returns "state-key"
        every { record.value() } returns "state-value"
        val records = mockk<KafkaConsumerRecords<String, String>>()
        every { records.isEmpty } returns false
        every { records.size() } returns 1
        every { records.recordAt(0) } returns record
        batch.captured.handle(records)
        yield()
    }

    suspend fun drain() = withContext(vertx.dispatcher()) {
        yield()
    }

    suspend fun awaitResume() = withTimeout(5_000L) {
        resumed.await()
        drain()
    }

    suspend fun close() {
        withContext(vertx.dispatcher()) {
            projection.close().coAwait()
            job.cancelAndJoin()
        }
        vertx.close().coAwait()
        unmockkStatic(KafkaConsumer::class)
    }

    private companion object {
        const val GROUP = "test-projection"
        const val TOPIC = "test.state.v1"
    }
}
