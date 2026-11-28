package com.cowork.applet.global.projection

import play.api.libs.json.{Json, OFormat}

// The durable recovery position of one partition. Field meanings follow the Kafka projection rules.
final case class ProjectionCheckpoint(
    nextOffset: Long,
    topicId: String,
    // Set when the stored position cannot be trusted. It latches the projection unready until an operator repairs it.
    invalidCheckpointOffset: Option[Long] = None,
    snapshotCompletedOffset: Option[Long] = None,
    // Set when a malformed record was quarantined. A distinct later full snapshot clears it.
    invalidRecordOffset: Option[Long] = None,
    lastSnapshotId: Option[String] = None,
    recoverySnapshotId: Option[String] = None
)

object ProjectionCheckpoint:
  given OFormat[ProjectionCheckpoint] = Json.format[ProjectionCheckpoint]

// The broker high-watermark observed when the partition was assigned. Readiness waits until the stream passes it.
final case class ProjectionBarrier(topicId: String, targetOffset: Long)

object ProjectionBarrier:
  given OFormat[ProjectionBarrier] = Json.format[ProjectionBarrier]

final case class BrokerRange(beginningOffset: Long, endOffset: Long)

final case class PartitionRange(partition: Int, beginningOffset: Long, endOffset: Long)

final case class TopicState(topicId: String, ranges: Map[Int, BrokerRange])

final case class SeekDecision(seekOffset: Long, topicId: String, invalidCheckpointOffset: Option[Long])
