package com.cowork.channel.global.projection

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.InterruptException
import org.apache.kafka.common.errors.WakeupException
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
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    /** 서비스 전체 readiness와 별개로 특정 aggregate가 기다리는 stream만 따라잡았는지 확인한다. */
    fun isCaughtUp(requiredStreams: Set<ProjectionStream>): Boolean {
        if (initializingStreams.any { it in requiredStreams }) return false
        return runCatching { checkpointStore.isCaughtUp(requiredStreams) }
            .onFailure { log.warn("Failed to verify Kafka projection readiness for {}", requiredStreams, it) }
            .getOrDefault(false)
    }

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
    private val retryBackoffMs: Long,
    private val retryTimeoutMs: Long,
) : ConsumerAwareRebalanceListener {
    private val log = LoggerFactory.getLogger(ProjectionAssignmentCoordinator::class.java)

    // Only accessed on the consumer thread. Limits stack traces to the first failure of a run.
    private var consecutiveFailures = 0

    override fun onPartitionsAssigned(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        readinessState.markInitializing(stream)
        // Retry inside the callback first: a rebalance would also take every healthy member out of readiness.
        // Keep the timeout well below max.poll.interval.ms, because one attempt can block for the client API timeout.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryTimeoutMs)
        while (true) {
            try {
                initializeAssignment(consumer, partitions)
                consecutiveFailures = 0
                return
            } catch (exception: WakeupException) {
                // The coordinator runs the callback again on the next poll.
                throw exception
            } catch (exception: InterruptException) {
                throw exception
            } catch (exception: InterruptedException) {
                // Future.get clears the interrupt flag, so restore it. The consumer is stopping.
                Thread.currentThread().interrupt()
                return
            } catch (exception: Exception) {
                // Catch checked exceptions too: the topic ID lookup throws TimeoutException and ExecutionException.
                logFailure(exception)
            }
            if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryBackoffMs) - deadline >= 0) {
                requestRebalance(consumer)
                return
            }
            try {
                Thread.sleep(retryBackoffMs)
            } catch (exception: InterruptedException) {
                // The consumer is stopping, so a new rebalance is not useful.
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun initializeAssignment(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        val topicId = topicIdentityProvider.topicId(stream.topic)
        val allPartitions = consumer.partitionsFor(stream.topic, CONSUMER_API_TIMEOUT)
            .map { TopicPartition(stream.topic, it.partition()) }
        check(allPartitions.isNotEmpty()) { "Kafka topic has no partitions: ${stream.topic}" }
        val beginningOffsets = consumer.beginningOffsets(allPartitions, CONSUMER_API_TIMEOUT)
        val endOffsets = consumer.endOffsets(allPartitions, CONSUMER_API_TIMEOUT)
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

    private fun logFailure(exception: Exception) {
        consecutiveFailures++
        if (consecutiveFailures == 1) {
            log.warn("Failed to initialize projection assignment for {}", stream.topic, exception)
        } else {
            log.warn(
                "Failed to initialize projection assignment for {} again, failure {}: {}",
                stream.topic,
                consecutiveFailures,
                exception.toString(),
            )
        }
    }

    private fun requestRebalance(consumer: Consumer<*, *>) {
        log.warn("Requesting a rebalance to retry projection assignment for {}", stream.topic)
        // Log the next failure with its stack trace, because its cause can differ after the rebalance.
        consecutiveFailures = 0
        // The container only logs listener failures, so without a new rebalance the stream would stay
        // initializing until restart. This depends on the classic group protocol: with group.protocol=consumer,
        // enforceRebalance only logs a warning.
        consumer.enforceRebalance("projection assignment initialization failed: ${stream.topic}")
    }

    override fun onPartitionsRevokedBeforeCommit(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        topicGenerations.markRevoked(stream)
        readinessState.markRevoked(stream)
    }

    private companion object {
        // Overrides default.api.timeout.ms (60 s) so that one attempt cannot hold the callback near
        // max.poll.interval.ms.
        val CONSUMER_API_TIMEOUT: Duration = Duration.ofSeconds(10)
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
    @Scheduled(fixedDelayString = $$"${projection.readiness.poll-interval-ms:1000}")
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
        if (!readinessState.isReady()) throw notReady()
    }

    /** 전역 readiness에 포함되지 않은 stream을 쓰는 기능이 해당 stream만 따라잡았는지 확인한다. */
    fun requireCaughtUp(streams: Set<ProjectionStream>) {
        if (!readinessState.isCaughtUp(streams)) throw notReady()
    }

    private fun notReady() = ExpectedException(
        "외부 상태 projection 동기화가 완료되지 않았습니다.",
        HttpStatus.SERVICE_UNAVAILABLE,
    )
}
