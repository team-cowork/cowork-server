package com.cowork.applet.global.projection

import com.cowork.applet.global.kafka.KafkaTopicInspector
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.{InterruptException, WakeupException}
import play.api.Logger
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

// Durable checkpoints and the broker topic UUID decide readiness, so every replica sees the same state.
// The scheduled refresh caches the result, so request-path checks never call the broker or the database.
final class ProjectionReadiness(
    projectionStore: ProjectionStore,
    kafkaTopicInspector: KafkaTopicInspector,
    val group: String,
    val topic: String
):
  private val logger = Logger(getClass)
  private val stateLock = Object()
  private val assignedTopicId = AtomicReference[Option[String]](None)
  @volatile private var initialized = false
  @volatile private var ready = false
  // Guarded by stateLock. A refresh that started before a rebalance must not publish its result.
  private var generation = 0L
  private var consecutiveFailures = 0

  def isReady: Boolean = initialized && ready

  def topicId: String =
    assignedTopicId.get().getOrElse(throw IllegalStateException(s"Kafka projection topic generation is not initialized for $topic."))

  // Retries inside the callback first, because a rebalance would also take healthy members out of readiness.
  def onAssigned(consumer: Consumer[?, ?], partitions: Iterable[TopicPartition]): Unit =
    invalidate()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ProjectionReadiness.AssignmentTimeoutSeconds)
    var done = false
    while !done do
      try
        initialize(consumer, partitions)
        consecutiveFailures = 0
        done = true
      catch
        case error @ (_: WakeupException | _: InterruptException) => throw error
        case NonFatal(error) =>
          consecutiveFailures += 1
          if consecutiveFailures == 1 then logger.warn(s"Failed to initialize the projection assignment for $topic.", error)
          else logger.warn(s"Failed to initialize the projection assignment for $topic again, failure $consecutiveFailures: $error")
          if System.nanoTime() + TimeUnit.SECONDS.toNanos(1) - deadline >= 0 then
            logger.warn(s"Requesting a rebalance to retry the projection assignment for $topic.")
            consumer.enforceRebalance("projection assignment initialization failed")
            done = true
          else Thread.sleep(1000)

  def onRevoked(): Unit =
    invalidate()
    assignedTopicId.set(None)

  def refresh(): Unit =
    val observed = stateLock.synchronized(generation)
    val evaluated = evaluate()
    stateLock.synchronized {
      if observed == generation then ready = evaluated
    }

  private def initialize(consumer: Consumer[?, ?], partitions: Iterable[TopicPartition]): Unit =
    val currentTopicId = kafkaTopicInspector.topicId(topic)
    val all = consumer.partitionsFor(topic).asScala.map(info => TopicPartition(topic, info.partition())).toSeq
    if all.isEmpty then throw IllegalStateException(s"Kafka topic $topic has no partitions.")
    val beginnings = consumer.beginningOffsets(all.asJava).asScala
    val ends = consumer.endOffsets(all.asJava).asScala
    val ranges = all.map(p => PartitionRange(p.partition(), beginnings(p).longValue, ends(p).longValue))
    val assigned = partitions.filter(_.topic() == topic).map(_.partition()).toSet
    val seeks = Await.result(projectionStore.initializeAssignment(group, topic, currentTopicId, ranges, assigned), 30.seconds)
    partitions.filter(_.topic() == topic).foreach { partition =>
      consumer.seek(partition, seeks.getOrElse(partition.partition(), throw IllegalStateException("Missing a projection seek offset.")))
    }
    assignedTopicId.set(Some(currentTopicId))
    initialized = true
    refresh()

  private def evaluate(): Boolean =
    if !initialized then false
    else
      try
        val barriers = Await.result(projectionStore.barriers(group, topic), 10.seconds)
        val checkpoints = Await.result(projectionStore.checkpoints(group, topic), 10.seconds)
        // Read the broker last so an append during the durable reads cannot leave readiness open.
        val broker = kafkaTopicInspector.topicState(topic)
        ProjectionPolicy.isReady(broker.topicId, broker.ranges, barriers, checkpoints)
      catch
        case NonFatal(error) =>
          logger.warn(s"Failed to evaluate the projection readiness for $topic.", error)
          false

  private def invalidate(): Unit = stateLock.synchronized {
    generation += 1
    initialized = false
    ready = false
  }

object ProjectionReadiness:
  // Stays well below max.poll.interval.ms, because one attempt can block for the client API timeout.
  private val AssignmentTimeoutSeconds = 60L
