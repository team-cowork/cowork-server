package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.repository.ProjectionPartitionRange
import com.cowork.preference.repository.QuarantinedProjectionRecord
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.kafka.client.common.TopicPartition
import io.vertx.kafka.client.consumer.KafkaConsumer
import io.vertx.kafka.client.consumer.KafkaConsumerRecord
import io.vertx.kafka.client.consumer.KafkaConsumerRecords
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

fun interface ProjectionRecordHandler {
    /** 유효한 record를 checkpoint와 같은 transaction에 반영하고, 계약 위반이면 격리 사유를 반환한다. */
    suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String?
}

class ProjectionStateConsumer(
    private val vertx: Vertx,
    bootstrapServers: String,
    private val groupId: String,
    private val topic: String,
    private val expectedSnapshotSource: String,
    private val handler: ProjectionRecordHandler,
    private val checkpointRepository: ProjectionCheckpointRepository,
    private val topicIdentity: ProjectionTopicIdentityProvider,
    private val readiness: ProjectionReadiness,
    scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(ProjectionStateConsumer::class.java)
    private val consumerJob = Job(scope.coroutineContext[Job])
    private val consumerScope = CoroutineScope(scope.coroutineContext + consumerJob + vertx.dispatcher())
    private val closePromise = Promise.promise<Void>()
    private val assignmentBlocked = AtomicBoolean(true)
    private val applyBlocked = AtomicBoolean(false)
    private val checkpointRangeBlocked = AtomicBoolean(false)
    private var assignmentScope: CoroutineScope? = null

    @Volatile
    private var assignedTopicId: String? = null

    @Volatile
    private var closed = false
    private val consumer = KafkaConsumer.create<String, String>(
        vertx,
        mapOf(
            "bootstrap.servers" to bootstrapServers,
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "group.id" to groupId,
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to "false",
            "isolation.level" to "read_committed",
        ),
    )

    init {
        consumerJob.invokeOnCompletion { error ->
            readiness.markUnavailable("$topic consumer stopped")
            consumer.close().onComplete { result ->
                when {
                    error != null && error !is CancellationException -> closePromise.fail(error)
                    result.failed() -> closePromise.fail(result.cause())
                    else -> closePromise.complete()
                }
            }
        }
    }

    fun start() {
        consumer.exceptionHandler { error ->
            if (closed || !consumerJob.isActive) return@exceptionHandler
            readiness.markUnavailable("$topic consumer failure")
            log.error("Kafka projection consumer failed topic={}", topic, error)
        }
        consumer.partitionsRevokedHandler { partitions ->
            if (closed || !consumerJob.isActive) return@partitionsRevokedHandler
            assignmentScope?.coroutineContext?.get(Job)?.cancel()
            assignmentBlocked.set(true)
            assignedTopicId = null
            readiness.markInitializing("Kafka partition rebalance in progress")
            // Keep polling for reassignment while record application remains blocked.
            consumer.resume()
            log.info("Kafka projection partitions revoked topic={} partitions={}", topic, partitions)
        }
        consumer.partitionsAssignedHandler(::initializeAssignedPartitions)
        consumer.batchHandler { records ->
            // Initialization seeks back to the database checkpoint, so pre-seek batches must be discarded.
            if (closed || assignmentBlocked.get() || checkpointRangeBlocked.get()) return@batchHandler
            val assignedScope = assignmentScope?.takeIf { it.isActive } ?: return@batchHandler
            consumer.pause()
            assignedScope.launch {
                processBatchWithRetry(records)
                currentCoroutineContext().ensureActive()
                consumer.resume()
            }
        }

        consumerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                subscribeWithRetry()
                awaitCancellation()
            } finally {
                readiness.markUnavailable("$topic consumer stopped")
            }
        }
    }

    fun close(): Future<Void> {
        closed = true
        assignmentBlocked.set(true)
        readiness.markUnavailable("$topic consumer stopped")
        consumerJob.cancel()
        return closePromise.future()
    }

    private suspend fun subscribeWithRetry() {
        retry("subscribe $topic consumer") {
            awaitConsumerOperation { consumer.subscribe(topic) }
        }
        log.info("Kafka projection consumer subscribed topic={} group={}", topic, groupId)
    }

    private fun initializeAssignedPartitions(partitions: Set<TopicPartition>) {
        if (closed || !consumerJob.isActive) return
        val previousJob = assignmentScope?.coroutineContext?.get(Job)
        previousJob?.cancel()
        val assignedScope = CoroutineScope(consumerScope.coroutineContext + Job(consumerJob))
        assignmentScope = assignedScope
        assignmentBlocked.set(true)
        applyBlocked.set(false)
        assignedTopicId = null
        readiness.markInitializing("initializing Kafka partition checkpoints")
        consumer.resume()
        assignedScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Enter the drain before dispatch so successive reassignments cannot skip an older worker's cleanup.
            withContext(NonCancellable) { previousJob?.join() }
            currentCoroutineContext().ensureActive()
            val localRangeViolation = initializeAssignedPartitionsWithRetry(partitions)
            checkpointRangeBlocked.set(localRangeViolation)
            assignmentBlocked.set(false)
            readiness.markInitialized()
            refreshReadiness()
            if (!checkpointRangeBlocked.get()) {
                retry("resume initialized Kafka projection partitions") {
                    awaitConsumerOperation { consumer.resume(partitions) }
                }
            }
            while (true) {
                delay(READINESS_REFRESH_INTERVAL_MS)
                refreshReadiness()
            }
        }
    }

    private suspend fun initializeAssignedPartitionsWithRetry(
        partitions: Set<TopicPartition>,
    ): Boolean = retry("initialize assigned partition checkpoints") {
        awaitConsumerOperation { consumer.pause(partitions) }
        val topicState = topicIdentity.topicState(topic)
        currentCoroutineContext().ensureActive()
        val result = checkpointRepository.initializeAssignment(
            consumerGroup = groupId,
            topic = topic,
            topicId = topicState.topicId,
            ranges = topicState.ranges.map { (partition, range) ->
                ProjectionPartitionRange(partition, range.beginningOffset, range.endOffset)
            },
            assignedPartitions = partitions.mapTo(mutableSetOf()) { it.partition },
        )
        currentCoroutineContext().ensureActive()
        partitions.sortedBy(TopicPartition::getPartition).forEach { partition ->
            val seekOffset = requireNotNull(result.seekOffsets[partition.partition])
            awaitConsumerOperation { consumer.seek(partition, seekOffset) }
            log.info(
                "Kafka projection partition initialized topic={} partition={} checkpoint={} topicId={}",
                topic,
                partition.partition,
                seekOffset,
                topicState.topicId,
            )
        }
        assignedTopicId = topicState.topicId
        result.invalidCheckpoint
    }

    private suspend fun processBatchWithRetry(records: KafkaConsumerRecords<String, String>) {
        val rangeIsValid = retry("validate batch checkpoint range") {
            validateBatchCheckpointRange(records)
        }
        if (!rangeIsValid) return
        for (index in 0 until records.size()) {
            processWithRetry(records.recordAt(index))
        }
    }

    private suspend fun validateBatchCheckpointRange(
        records: KafkaConsumerRecords<String, String>,
    ): Boolean {
        if (records.isEmpty) return true
        val checkpoints = checkpointRepository.load(groupId, topic)
        currentCoroutineContext().ensureActive()
        val topicState = topicIdentity.topicState(topic)
        currentCoroutineContext().ensureActive()
        val rangeViolation = hasRangeViolation(checkpoints, topicState)
        if (rangeViolation) {
            checkpointRangeBlocked.set(true)
            readiness.markUnavailable("database checkpoint is outside the retained Kafka range")
            pauseAssignedPartitions()
        }
        return !rangeViolation
    }

    private suspend fun processWithRetry(record: KafkaConsumerRecord<String, String>) {
        val partition = TopicPartition(record.topic(), record.partition())
        while (true) {
            currentCoroutineContext().ensureActive()
            val checkpoint = checkpoint(record.partition(), record.offset() + 1)
            try {
                processRecord(checkpoint, record)
                retry("align consumer position with database checkpoint") {
                    awaitConsumerOperation { consumer.seek(partition, checkpoint.nextOffset) }
                }
                applyBlocked.set(false)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                applyBlocked.set(true)
                readiness.markUnavailable("$topic projection apply failed")
                log.error(
                    "Failed to apply Kafka projection record; retrying topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    error,
                )
                retry("rewind consumer position after failed projection apply") {
                    awaitConsumerOperation { consumer.seek(partition, record.offset()) }
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun processRecord(
        checkpoint: ProjectionCheckpoint,
        record: KafkaConsumerRecord<String, String>,
    ) {
        if (ProjectionSnapshotCompletion.isReserved(record.key())) {
            val violation = ProjectionSnapshotCompletion.violation(
                record.key(),
                record.value(),
                record.topic(),
                record.partition(),
                expectedSnapshotSource,
            )
            if (violation == null) {
                checkpointRepository.completeSnapshot(checkpoint, record.offset())
            } else {
                quarantine(checkpoint, record, violation)
            }
            return
        }
        handler.apply(checkpoint, record.key(), record.value())?.let { reason ->
            quarantine(checkpoint, record, reason)
        }
    }

    private suspend fun quarantine(
        checkpoint: ProjectionCheckpoint,
        record: KafkaConsumerRecord<String, String>,
        reason: String,
    ) {
        currentCoroutineContext().ensureActive()
        checkpointRepository.quarantineAndAdvance(
            checkpoint,
            QuarantinedProjectionRecord(record.key(), record.value(), reason),
        )
        currentCoroutineContext().ensureActive()
        checkpointRangeBlocked.set(true)
        readiness.markUnavailable("$topic invalid projection record requires manual rebuild")
        pauseAssignedPartitions()
        log.error(
            "Quarantined invalid projection record topic={} partition={} offset={} reason={}",
            record.topic(),
            record.partition(),
            record.offset(),
            reason,
        )
    }

    private suspend fun refreshReadiness() {
        currentCoroutineContext().ensureActive()
        if (assignmentBlocked.get()) return
        runCatching {
            val checkpoints = checkpointRepository.load(groupId, topic)
            currentCoroutineContext().ensureActive()
            val barriers = checkpointRepository.loadBarriers(groupId, topic)
            currentCoroutineContext().ensureActive()
            val topicState = topicIdentity.topicState(topic)
            currentCoroutineContext().ensureActive()
            if (hasRangeViolation(checkpoints, topicState)) {
                checkpointRangeBlocked.set(true)
                pauseAssignedPartitions()
            }
            if (assignmentBlocked.get() || applyBlocked.get() || checkpointRangeBlocked.get()) {
                readiness.markUnavailable("$topic consumer is not ready to apply records")
            } else {
                readiness.evaluate(barriers, checkpoints, topicState)
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            currentCoroutineContext().ensureActive()
            readiness.markUnavailable("projection readiness verification failed")
            log.warn("Unable to verify Kafka projection readiness topic={}", topic, error)
        }
    }

    private suspend fun pauseAssignedPartitions() {
        val assignments = awaitConsumerOperation { consumer.assignment() }
        if (assignments.isNotEmpty()) awaitConsumerOperation { consumer.pause(assignments) }
    }

    private fun hasRangeViolation(
        checkpoints: Map<Int, ProjectionCheckpoint>,
        topicState: ProjectionTopicState,
    ): Boolean = checkpoints.keys != topicState.ranges.keys || topicState.ranges.any { (partition, range) ->
        checkpoints[partition]?.let { checkpoint ->
            checkpoint.topicId != topicState.topicId ||
                checkpoint.invalidCheckpointOffset != null ||
                checkpoint.invalidRecordOffset != null ||
                checkpoint.nextOffset !in range.beginningOffset..range.endOffset
        } ?: true
    }

    private fun checkpoint(partition: Int, nextOffset: Long): ProjectionCheckpoint = ProjectionCheckpoint(
        consumerGroup = groupId,
        topic = topic,
        partition = partition,
        nextOffset = nextOffset,
        topicId = requireNotNull(assignedTopicId) { "Kafka topic generation is not assigned" },
    )

    private suspend fun <T> retry(operation: String, block: suspend () -> T): T {
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val result = block()
                currentCoroutineContext().ensureActive()
                return result
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                log.error("Failed to {}; retrying", operation, error)
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun <T> awaitConsumerOperation(operation: () -> Future<T>): T {
        currentCoroutineContext().ensureActive()
        // Kafka commands already queued on its worker must settle before assignment replacement or close.
        val result = withContext(NonCancellable) { operation().coAwait() }
        currentCoroutineContext().ensureActive()
        return result
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_000L
        const val READINESS_REFRESH_INTERVAL_MS = 1_000L
    }
}
