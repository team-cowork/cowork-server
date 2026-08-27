package com.cowork.channel.global.projection

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.ApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import java.util.concurrent.ConcurrentHashMap

@Component
class ProjectionReadinessState(
    private val checkpointStore: ProjectionCheckpointStore,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(ProjectionReadinessState::class.java)
    private val assignedStreams = ConcurrentHashMap.newKeySet<ProjectionStream>()
    private val initializingStreams = ConcurrentHashMap.newKeySet<ProjectionStream>().apply {
        addAll(streams.required)
    }

    @Volatile
    private var ready = false

    fun markInitializing(stream: ProjectionStream) {
        if (stream !in streams.required) return
        initializingStreams.add(stream)
        ready = false
    }

    fun markInitialized(stream: ProjectionStream, locallyAssigned: Boolean) {
        if (stream !in streams.required) return
        initializingStreams.remove(stream)
        if (locallyAssigned) assignedStreams.add(stream) else assignedStreams.remove(stream)
        refresh()
    }

    fun markRevoked(stream: ProjectionStream) {
        if (stream !in streams.required) return
        assignedStreams.remove(stream)
        refresh()
    }

    fun markNotReady(stream: ProjectionStream) {
        if (stream in streams.required) ready = false
    }

    fun refresh(): Boolean {
        ready = if (initializingStreams.isNotEmpty()) {
            false
        } else {
            runCatching { checkpointStore.isCaughtUp(streams.required) }
                .onFailure { log.warn("Kafka projection readiness 조회 실패", it) }
                .getOrDefault(false)
        }
        return ready
    }

    fun isReady(): Boolean = ready

    fun assignedCount(): Int = assignedStreams.size

    fun requiredCount(): Int = streams.required.size

    fun initializingCount(): Int = initializingStreams.size
}

class ProjectionAssignmentCoordinator(
    private val stream: ProjectionStream,
    private val checkpointStore: ProjectionCheckpointStore,
    private val readinessState: ProjectionReadinessState,
    private val topicIdentityProvider: ProjectionTopicIdentityProvider,
    private val topicGenerations: ProjectionTopicGenerationRegistry,
) : ConsumerAwareRebalanceListener {
    override fun onPartitionsAssigned(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        readinessState.markInitializing(stream)
        val topicId = topicIdentityProvider.topicId(stream.topic)
        val allPartitions = consumer.partitionsFor(stream.topic).map { TopicPartition(stream.topic, it.partition()) }
        check(allPartitions.isNotEmpty()) { "Kafka topic has no partitions: ${stream.topic}" }
        val beginningOffsets = consumer.beginningOffsets(allPartitions)
        val endOffsets = consumer.endOffsets(allPartitions)
        val ranges = allPartitions.map { partition ->
            ProjectionPartitionRange(
                partition = partition.partition(),
                beginningOffset = requireNotNull(beginningOffsets[partition]),
                endOffset = requireNotNull(endOffsets[partition]),
            )
        }
        val assignedPartitions = partitions.filter { it.topic() == stream.topic }
        val seekOffsets = checkpointStore.initializeAssignment(
            stream,
            topicId,
            ranges,
            assignedPartitions.mapTo(mutableSetOf()) { it.partition() },
        )
        assignedPartitions.forEach { partition ->
            consumer.seek(partition, requireNotNull(seekOffsets[partition.partition()]))
        }
        topicGenerations.markAssigned(stream, topicId)
        readinessState.markInitialized(stream, assignedPartitions.isNotEmpty())
    }

    override fun onPartitionsRevokedBeforeCommit(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        topicGenerations.markRevoked(stream)
        readinessState.markRevoked(stream)
    }
}

@Component("projectionReadiness")
class ProjectionReadinessHealthIndicator(private val readinessState: ProjectionReadinessState) : HealthIndicator {
    override fun health(): Health = if (readinessState.refresh()) {
        Health.up().build()
    } else {
        Health.outOfService()
            .withDetail("localAssignedStreams", readinessState.assignedCount())
            .withDetail("initializingStreams", readinessState.initializingCount())
            .withDetail("requiredStreams", readinessState.requiredCount())
            .build()
    }
}

@Component
class ProjectionReadinessAvailability(
    private val readinessState: ProjectionReadinessState,
    private val applicationContext: ApplicationContext,
) {
    @Scheduled(fixedDelayString = "\${projection.readiness.poll-interval-ms:1000}")
    fun refreshAvailability() {
        val state = if (readinessState.refresh()) {
            ReadinessState.ACCEPTING_TRAFFIC
        } else {
            ReadinessState.REFUSING_TRAFFIC
        }
        AvailabilityChangeEvent.publish(applicationContext, state)
    }

    @EventListener
    fun preserveFailClosedReadiness(event: AvailabilityChangeEvent<*>) {
        if (event.state == ReadinessState.ACCEPTING_TRAFFIC && !readinessState.isReady()) {
            AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
        }
    }
}

@Component
class ProjectionReadinessGate(private val readinessState: ProjectionReadinessState) {
    fun requireReady() {
        if (!readinessState.isReady()) {
            throw ExpectedException(
                "외부 상태 projection 동기화가 완료되지 않았습니다.",
                HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
    }
}
