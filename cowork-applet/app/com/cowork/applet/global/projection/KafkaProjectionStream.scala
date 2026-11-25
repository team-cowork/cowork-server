package com.cowork.applet.global.projection

import java.time.Duration
import java.util.{Collection as JavaCollection, List as JavaList, Properties}
import org.apache.kafka.clients.consumer.{ConsumerRebalanceListener, ConsumerRecord, KafkaConsumer}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import play.api.Logger
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

// Applies one topic to its projection on a dedicated thread. Broker group offsets are never committed or used:
// the stored checkpoint is the only recovery authority. A storage failure seeks back so the record is retried.
final class KafkaProjectionStream(
    clientProperties: Properties,
    projectionStore: ProjectionStore,
    val readiness: ProjectionReadiness,
    handler: ProjectionRecordHandler
):
  private val logger = Logger(getClass)
  @volatile private var running = true

  private val consumer =
    val properties = Properties()
    properties.putAll(clientProperties)
    properties.put("group.id", handler.group)
    properties.put("enable.auto.commit", "false")
    properties.put("auto.offset.reset", "earliest")
    properties.put("max.poll.records", "100")
    properties.put("key.deserializer", classOf[StringDeserializer].getName)
    properties.put("value.deserializer", classOf[StringDeserializer].getName)
    KafkaConsumer[String, String](properties)

  private val thread = Thread(() => run(), s"projection-${handler.topic}")
  thread.setDaemon(true)

  def start(): Unit = thread.start()

  def stop(): Unit =
    running = false
    consumer.wakeup()
    thread.join(10_000)

  private def run(): Unit =
    try
      consumer.subscribe(
        JavaList.of(handler.topic),
        new ConsumerRebalanceListener:
          override def onPartitionsAssigned(partitions: JavaCollection[TopicPartition]): Unit =
            readiness.onAssigned(consumer, partitions.asScala)
          override def onPartitionsRevoked(partitions: JavaCollection[TopicPartition]): Unit = readiness.onRevoked()
      )
      while running do
        val records = consumer.poll(Duration.ofSeconds(1)).asScala.iterator
        var failed = false
        while !failed && records.hasNext do
          val record = records.next()
          try process(record)
          catch
            case error: WakeupException => throw error
            case NonFatal(error) =>
              logger.warn(s"Failed to apply ${handler.topic} offset ${record.offset()}. Retrying it.", error)
              consumer.seek(TopicPartition(record.topic(), record.partition()), record.offset())
              failed = true
              Thread.sleep(1000)
    catch
      case _: WakeupException if !running => ()
      case NonFatal(error)                => logger.error(s"Stopped the ${handler.topic} projection stream.", error)
    finally consumer.close(Duration.ofSeconds(5))

  private def process(record: ConsumerRecord[String, String]): Unit =
    val topicId = readiness.topicId
    val partition = record.partition()
    val previous = Await
      .result(projectionStore.checkpoints(handler.group, handler.topic), 10.seconds)
      .get(partition)
      .filter(_.topicId == topicId)
      .getOrElse(throw IllegalStateException("Kafka projection checkpoint generation mismatch"))
    val next = record.offset() + 1
    val (checkpoint, mutation, quarantined) =
      if SnapshotMarker.isReserved(record.key()) then
        SnapshotMarker.parse(record.topic(), partition, record.key(), record.value(), handler.snapshotSource) match
          case Right(snapshotId) =>
            (ProjectionPolicy.markSnapshotCompleted(previous, record.offset(), snapshotId), None, None)
          case Left(reason) => invalid(previous, record, reason)
      else
        handler.mutation(record) match
          case Right(change) => (previous, Some(change), None)
          case Left(reason)  => invalid(previous, record, reason)
    quarantined.foreach(q => logger.error(s"Quarantined ${handler.topic} offset ${q.offset}: ${q.reason}"))
    Await.result(
      projectionStore.commit(
        handler.group,
        handler.topic,
        partition,
        previous,
        ProjectionPolicy.advance(checkpoint, next),
        mutation,
        quarantined
      ),
      30.seconds
    )

  private def invalid(previous: ProjectionCheckpoint, record: ConsumerRecord[String, String], reason: String) =
    (
      ProjectionPolicy.markInvalidRecord(previous, record.offset()),
      None,
      Some(QuarantinedRecord(record.partition(), record.offset(), Option(record.key()), Option(record.value()), reason))
    )
