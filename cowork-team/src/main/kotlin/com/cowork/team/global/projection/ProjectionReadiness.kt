package com.cowork.team.global.projection

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.InterruptException
import org.apache.kafka.common.errors.WakeupException
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
import team.themoment.sdk.logging.logger.logger
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
class ProjectionReadinessState(
    private val projectionCheckpointStore: ProjectionCheckpointStore,
    private val projectionStreams: ProjectionStreams,
) {
    private val assignedStreams = ConcurrentHashMap.newKeySet<ProjectionStream>()
    private val initializingStreams = ConcurrentHashMap.newKeySet<ProjectionStream>().apply {
        addAll(projectionStreams.required)
        addAll(projectionStreams.calendar)
    }

    @Volatile
    private var ready = false

    @Volatile
    private var calendarReady = false

    fun markInitializing(stream: ProjectionStream) {
        initializingStreams.add(stream)
        markNotReady(stream)
    }

    fun markInitialized(stream: ProjectionStream, locallyAssigned: Boolean) {
        initializingStreams.remove(stream)
        if (locallyAssigned) assignedStreams.add(stream) else assignedStreams.remove(stream)
        refresh()
    }

    fun markRevoked(stream: ProjectionStream) {
        assignedStreams.remove(stream)
        refresh()
    }

    fun markNotReady(stream: ProjectionStream) {
        if (stream in projectionStreams.required) ready = false
        if (stream in projectionStreams.calendar) calendarReady = false
    }

    /** 서비스 readiness를 돌려준다. 팀 캘린더 projection도 함께 다시 확인하지만 결과에는 넣지 않는다. */
    fun refresh(): Boolean {
        ready = isCaughtUp(projectionStreams.required)
        calendarReady = isCaughtUp(projectionStreams.calendar)
        return ready
    }

    private fun isCaughtUp(streams: Set<ProjectionStream>): Boolean {
        if (initializingStreams.any { it in streams }) return false
        return runCatching { projectionCheckpointStore.isCaughtUp(streams) }
            .onFailure { logger().warn("Kafka projection readiness 조회 실패", it) }
            .getOrDefault(false)
    }

    fun isReady(): Boolean = ready

    fun isCalendarReady(): Boolean = calendarReady

    fun assignedCount(): Int = assignedStreams.size

    fun requiredCount(): Int = projectionStreams.required.size

    fun initializingCount(): Int = initializingStreams.size
}
class ProjectionAssignmentCoordinator(
    private val projectionStream: ProjectionStream,
    private val projectionCheckpointStore: ProjectionCheckpointStore,
    private val projectionReadinessState: ProjectionReadinessState,
    private val projectionTopicIdentityProvider: ProjectionTopicIdentityProvider,
    private val projectionTopicGenerationRegistry: ProjectionTopicGenerationRegistry,
    private val retryBackoffMs: Long,
    private val retryTimeoutMs: Long,
) : ConsumerAwareRebalanceListener {

    // Only accessed on the consumer thread. Limits stack traces to the first failure of a run.
    private var consecutiveFailures = 0

    override fun onPartitionsAssigned(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        projectionReadinessState.markInitializing(projectionStream)
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
        val topicId = projectionTopicIdentityProvider.topicId(projectionStream.topic)
        val allPartitions = consumer.partitionsFor(projectionStream.topic, CONSUMER_API_TIMEOUT)
            .map { TopicPartition(projectionStream.topic, it.partition()) }
        check(allPartitions.isNotEmpty()) { "Kafka topic has no partitions: ${projectionStream.topic}" }
        val beginningOffsets = consumer.beginningOffsets(allPartitions, CONSUMER_API_TIMEOUT)
        val endOffsets = consumer.endOffsets(allPartitions, CONSUMER_API_TIMEOUT)
        val ranges = allPartitions.map { partition ->
            ProjectionPartitionRange(
                partition = partition.partition(),
                beginningOffset = requireNotNull(beginningOffsets[partition]),
                endOffset = requireNotNull(endOffsets[partition]),
            )
        }
        val assignedPartitions = partitions.filter { it.topic() == projectionStream.topic }
        val seekOffsets = projectionCheckpointStore.initializeAssignment(
            projectionStream,
            topicId,
            ranges,
            assignedPartitions.mapTo(mutableSetOf()) { it.partition() },
        )
        assignedPartitions.forEach { partition ->
            consumer.seek(partition, requireNotNull(seekOffsets[partition.partition()]))
        }
        projectionTopicGenerationRegistry.markAssigned(projectionStream, topicId)
        projectionReadinessState.markInitialized(projectionStream, assignedPartitions.isNotEmpty())
    }

    private fun logFailure(exception: Exception) {
        consecutiveFailures++
        if (consecutiveFailures == 1) {
            logger().warn("Failed to initialize projection assignment for {}", projectionStream.topic, exception)
        } else {
            logger().warn(
                "Failed to initialize projection assignment for {} again, failure {}: {}",
                projectionStream.topic,
                consecutiveFailures,
                exception.toString(),
            )
        }
    }

    private fun requestRebalance(consumer: Consumer<*, *>) {
        logger().warn("Requesting a rebalance to retry projection assignment for {}", projectionStream.topic)
        // Log the next failure with its stack trace, because its cause can differ after the rebalance.
        consecutiveFailures = 0
        // The container only logs listener failures, so without a new rebalance the stream would stay
        // initializing until restart. This depends on the classic group protocol: with group.protocol=consumer,
        // enforceRebalance only logs a warning.
        consumer.enforceRebalance("projection assignment initialization failed: ${projectionStream.topic}")
    }

    override fun onPartitionsRevokedBeforeCommit(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
        projectionTopicGenerationRegistry.markRevoked(projectionStream)
        projectionReadinessState.markRevoked(projectionStream)
    }

    private companion object {
        // Overrides default.api.timeout.ms (60 s) so that one attempt cannot hold the callback near
        // max.poll.interval.ms.
        val CONSUMER_API_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

@Component("projectionReadiness")
class ProjectionReadinessHealthIndicator(private val projectionReadinessState: ProjectionReadinessState) :
    HealthIndicator {
    override fun health(): Health = if (projectionReadinessState.refresh()) {
        Health.up().build()
    } else {
        Health.outOfService()
            .withDetail("localAssignedStreams", projectionReadinessState.assignedCount())
            .withDetail("initializingStreams", projectionReadinessState.initializingCount())
            .withDetail("requiredStreams", projectionReadinessState.requiredCount())
            .build()
    }
}

@Component
class ProjectionReadinessAvailability(
    private val projectionReadinessState: ProjectionReadinessState,
    private val applicationContext: ApplicationContext,
) {
    @Scheduled(fixedDelayString = $$"${projection.readiness.poll-interval-ms:1000}")
    fun refreshAvailability() {
        val state = if (projectionReadinessState.refresh()) {
            ReadinessState.ACCEPTING_TRAFFIC
        } else {
            ReadinessState.REFUSING_TRAFFIC
        }
        AvailabilityChangeEvent.publish(applicationContext, state)
    }

    @EventListener
    fun preserveFailClosedReadiness(event: AvailabilityChangeEvent<*>) {
        if (event.state == ReadinessState.ACCEPTING_TRAFFIC && !projectionReadinessState.isReady()) {
            AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
        }
    }
}

@Component
class ProjectionReadinessGate(private val projectionReadinessState: ProjectionReadinessState) {
    fun requireReady() {
        if (!projectionReadinessState.isReady()) {
            throw ExpectedException(
                "외부 상태 projection 동기화가 완료되지 않았습니다.",
                HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
    }

    fun requireCalendarReady() {
        if (!projectionReadinessState.isCalendarReady()) {
            throw ExpectedException("프로젝트 일정 동기화가 완료되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
    }
}
