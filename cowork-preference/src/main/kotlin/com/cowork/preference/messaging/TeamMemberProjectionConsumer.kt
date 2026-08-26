package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.repository.ProjectionPartitionRange
import com.cowork.preference.repository.QuarantinedProjectionRecord
import com.cowork.preference.service.TeamRoleService
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.kafka.client.common.TopicPartition
import io.vertx.kafka.client.consumer.KafkaConsumer
import io.vertx.kafka.client.consumer.KafkaConsumerRecord
import io.vertx.kafka.client.consumer.KafkaConsumerRecords
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TeamMemberProjectionConsumer(
    private val vertx: Vertx,
    bootstrapServers: String,
    private val groupId: String,
    private val topic: String,
    private val teamRoleService: TeamRoleService,
    private val checkpointRepository: ProjectionCheckpointRepository,
    private val topicIdentity: ProjectionTopicIdentityProvider,
    private val readiness: ProjectionReadiness,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(TeamMemberProjectionConsumer::class.java)
    private val refreshInProgress = AtomicBoolean(false)
    private val assignmentBlocked = AtomicBoolean(true)
    private val applyBlocked = AtomicBoolean(false)
    private val checkpointRangeBlocked = AtomicBoolean(false)
    private val assignmentGeneration = AtomicLong()

    @Volatile
    private var assignedTopicId: String? = null
    private var closed = false
    private var readinessTimerId: Long? = null
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

    fun start() {
        consumer.exceptionHandler { error ->
            readiness.markUnavailable("team.member.event consumer failure")
            log.error("team.member.event consumer failure", error)
        }
        consumer.partitionsRevokedHandler { partitions ->
            assignmentGeneration.incrementAndGet()
            assignmentBlocked.set(true)
            assignedTopicId = null
            readiness.markInitializing("Kafka partition rebalance in progress")
            log.info("team.member.event partitions revoked partitions={}", partitions)
        }
        consumer.partitionsAssignedHandler(::initializeAssignedPartitions)
        consumer.batchHandler { records ->
            val generation = assignmentGeneration.get()
            consumer.pause()
            scope.launch(vertx.dispatcher()) {
                processBatchWithRetry(records, generation)
                refreshReadiness()
                if (!closed && generation == assignmentGeneration.get() && !checkpointRangeBlocked.get()) {
                    consumer.resume()
                }
            }
        }

        scope.launch(vertx.dispatcher()) {
            subscribeWithRetry()
            readinessTimerId = vertx.setPeriodic(READINESS_REFRESH_INTERVAL_MS) {
                refreshReadinessAsync()
            }
        }
    }

    fun close(): Future<Void> {
        closed = true
        assignmentGeneration.incrementAndGet()
        readinessTimerId?.let(vertx::cancelTimer)
        return consumer.close()
    }

    private suspend fun subscribeWithRetry() {
        retry("subscribe team.member.event consumer") {
            consumer.subscribe(topic).coAwait()
        }
        log.info("team.member.event consumer subscribed topic={} group={}", topic, groupId)
    }

    private fun initializeAssignedPartitions(partitions: Set<TopicPartition>) {
        val generation = assignmentGeneration.incrementAndGet()
        assignmentBlocked.set(true)
        readiness.markInitializing("initializing Kafka partition checkpoints")
        consumer.pause(partitions)
        scope.launch(vertx.dispatcher()) {
            checkpointRangeBlocked.set(false)
            val localRangeViolation = initializeAssignedPartitionsWithRetry(partitions, generation)
            checkpointRangeBlocked.set(localRangeViolation)
            assignmentBlocked.set(false)
            readiness.markInitialized()
            refreshReadiness()
            if (generation == assignmentGeneration.get() && !closed && !checkpointRangeBlocked.get()) {
                runCatching { consumer.resume(partitions).coAwait() }
                    .onFailure { log.warn("Failed to resume initialized team.member.event partitions", it) }
            }
        }
    }

    private suspend fun initializeAssignedPartitionsWithRetry(
        partitions: Set<TopicPartition>,
        generation: Long,
    ): Boolean = retry("initialize assigned partition checkpoints", generation) {
        val topicState = topicIdentity.topicState(topic)
        val result = checkpointRepository.initializeAssignment(
            consumerGroup = groupId,
            topic = topic,
            topicId = topicState.topicId,
            ranges = topicState.ranges.map { (partition, range) ->
                ProjectionPartitionRange(partition, range.beginningOffset, range.endOffset)
            },
            assignedPartitions = partitions.mapTo(mutableSetOf()) { it.partition },
        )
        assignedTopicId = topicState.topicId
        partitions.sortedBy(TopicPartition::getPartition).forEach { partition ->
            val seekOffset = requireNotNull(result.seekOffsets[partition.partition])
            consumer.seek(partition, seekOffset).coAwait()
            log.info(
                "team.member.event partition initialized partition={} checkpoint={} topicId={}",
                partition.partition,
                seekOffset,
                topicState.topicId,
            )
        }
        result.invalidCheckpoint
    }

    private suspend fun processBatchWithRetry(records: KafkaConsumerRecords<String, String>, generation: Long) {
        val rangeIsValid = retry("validate batch checkpoint range", generation) {
            validateBatchCheckpointRange(records)
        }
        if (!rangeIsValid) return
        for (index in 0 until records.size()) {
            processWithRetry(records.recordAt(index), generation)
        }
    }

    private suspend fun validateBatchCheckpointRange(records: KafkaConsumerRecords<String, String>): Boolean {
        if (records.isEmpty) return true
        val checkpoints = checkpointRepository.load(groupId, topic)
        val topicState = topicIdentity.topicState(topic)
        val rangeViolation = hasRangeViolation(checkpoints, topicState)
        if (rangeViolation) {
            checkpointRangeBlocked.set(true)
            readiness.markUnavailable("database checkpoint is outside the retained Kafka range")
        }
        return !rangeViolation
    }

    private suspend fun processWithRetry(record: KafkaConsumerRecord<String, String>, generation: Long) {
        val partition = TopicPartition(record.topic(), record.partition())
        while (!closed && generation == assignmentGeneration.get()) {
            val checkpoint = checkpoint(record.partition(), record.offset() + 1)
            try {
                processRecord(checkpoint, record)
                retry("align consumer position with database checkpoint", generation) {
                    consumer.seek(partition, checkpoint.nextOffset).coAwait()
                }
                applyBlocked.set(false)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                applyBlocked.set(true)
                readiness.markUnavailable("team.member.event projection apply failed")
                log.error(
                    "Failed to apply team.member.event record; retrying topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    error,
                )
                retry("rewind consumer position after failed projection apply", generation) {
                    consumer.seek(partition, record.offset()).coAwait()
                }
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("team.member.event assignment changed while applying a record")
    }

    private suspend fun processRecord(checkpoint: ProjectionCheckpoint, record: KafkaConsumerRecord<String, String>) {
        if (ProjectionSnapshotCompletion.isReserved(record.key())) {
            val violation = ProjectionSnapshotCompletion.violation(
                record.key(),
                record.value(),
                record.topic(),
                record.partition(),
            )
            if (violation == null) {
                checkpointRepository.completeSnapshot(checkpoint, record.offset())
            } else {
                quarantine(checkpoint, record, violation)
            }
            return
        }
        when (val decision = TeamMemberEventParser.parse(record.key(), record.value())) {
            is TeamMemberRecordDecision.ApplyDelete -> applyDelete(checkpoint, decision.event)
            is TeamMemberRecordDecision.IgnoreUpsert -> checkpointRepository.advance(checkpoint)
            is TeamMemberRecordDecision.Quarantine -> quarantine(checkpoint, record, decision.reason)
        }
    }

    private suspend fun quarantine(
        checkpoint: ProjectionCheckpoint,
        record: KafkaConsumerRecord<String, String>,
        reason: String,
    ) {
        checkpointRepository.quarantineAndAdvance(
            checkpoint,
            QuarantinedProjectionRecord(record.key(), record.value(), reason),
        )
        log.error(
            "Quarantined invalid projection record topic={} partition={} offset={} reason={}",
            record.topic(),
            record.partition(),
            record.offset(),
            reason,
        )
    }

    private suspend fun applyDelete(checkpoint: ProjectionCheckpoint, event: TeamMemberEvent) {
        checkpointRepository.inTransaction(checkpoint) { connection ->
            when (TeamMemberCleanupPolicy.scope(event)) {
                TeamMemberCleanupScope.TEAM -> {
                    teamRoleService.deleteTeamRoles(connection, event.teamId, event.occurredAt)
                }
                TeamMemberCleanupScope.MEMBER -> {
                    teamRoleService.removeMemberRolesAtOrBefore(
                        connection,
                        event.userId,
                        event.teamId,
                        event.occurredAt,
                    )
                }
            }
        }
    }

    private fun refreshReadinessAsync() {
        if (!refreshInProgress.compareAndSet(false, true)) return
        scope.launch(vertx.dispatcher()) {
            try {
                refreshReadiness()
            } finally {
                refreshInProgress.set(false)
            }
        }
    }

    private suspend fun refreshReadiness() {
        runCatching {
            val topicState = topicIdentity.topicState(topic)
            val checkpoints = checkpointRepository.load(groupId, topic)
            val barriers = checkpointRepository.loadBarriers(groupId, topic)
            if (hasRangeViolation(checkpoints, topicState)) {
                checkpointRangeBlocked.set(true)
                val assignments = consumer.assignment().coAwait()
                if (assignments.isNotEmpty()) consumer.pause(assignments).coAwait()
            }
            if (assignmentBlocked.get() || applyBlocked.get() || checkpointRangeBlocked.get()) {
                readiness.markUnavailable("team.member.event consumer is not ready to apply records")
            } else {
                readiness.evaluate(barriers, checkpoints, topicState)
            }
        }.onFailure { error ->
            readiness.markUnavailable("projection readiness verification failed")
            log.warn("Unable to verify team.member.event projection readiness", error)
        }
    }

    private fun hasRangeViolation(
        checkpoints: Map<Int, ProjectionCheckpoint>,
        topicState: ProjectionTopicState,
    ): Boolean = checkpoints.keys != topicState.ranges.keys || topicState.ranges.any { (partition, range) ->
        checkpoints[partition]?.let { checkpoint ->
            checkpoint.topicId != topicState.topicId ||
                checkpoint.invalidCheckpointOffset != null ||
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

    private suspend fun <T> retry(operation: String, requiredGeneration: Long? = null, block: suspend () -> T): T {
        while (!closed && (requiredGeneration == null || requiredGeneration == assignmentGeneration.get())) {
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Failed to {}; retrying", operation, error)
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("team.member.event consumer stopped while attempting to $operation")
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_000L
        const val READINESS_REFRESH_INTERVAL_MS = 1_000L
    }
}
