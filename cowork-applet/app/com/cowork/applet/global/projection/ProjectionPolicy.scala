package com.cowork.applet.global.projection

// Pure checkpoint rules, ported from the Roadmap projection so every JVM consumer behaves the same way.
object ProjectionPolicy:

  def seekDecision(checkpoint: Option[ProjectionCheckpoint], range: PartitionRange, currentTopicId: String): SeekDecision =
    checkpoint match
      case None => SeekDecision(range.beginningOffset, currentTopicId, None)
      case Some(stored) if stored.topicId != currentTopicId =>
        SeekDecision(range.beginningOffset, currentTopicId, Some(stored.nextOffset))
      case Some(stored) if stored.invalidCheckpointOffset.isDefined =>
        val safe =
          if inRange(stored.nextOffset, range) then stored.nextOffset else range.beginningOffset
        SeekDecision(safe, currentTopicId, stored.invalidCheckpointOffset)
      case Some(stored) if !inRange(stored.nextOffset, range) =>
        SeekDecision(range.beginningOffset, currentTopicId, Some(stored.nextOffset))
      case Some(stored) => SeekDecision(stored.nextOffset, currentTopicId, None)

  // A topic generation change keeps the old position as evidence and latches the checkpoint invalid.
  def ensure(existing: Option[ProjectionCheckpoint], decision: SeekDecision): ProjectionCheckpoint =
    existing match
      case None => ProjectionCheckpoint(decision.seekOffset, decision.topicId, decision.invalidCheckpointOffset)
      case Some(stored) if stored.topicId != decision.topicId =>
        ProjectionCheckpoint(stored.nextOffset, decision.topicId, invalidCheckpointOffset = Some(stored.nextOffset))
      case Some(stored) =>
        stored.copy(invalidCheckpointOffset = stored.invalidCheckpointOffset.orElse(decision.invalidCheckpointOffset))

  def barrier(existing: Option[ProjectionBarrier], topicId: String, endOffset: Long): ProjectionBarrier =
    existing match
      case Some(stored) if stored.topicId == topicId => ProjectionBarrier(topicId, stored.targetOffset.max(endOffset))
      case _                                         => ProjectionBarrier(topicId, endOffset)

  def advance(checkpoint: ProjectionCheckpoint, nextOffset: Long): ProjectionCheckpoint =
    checkpoint.copy(nextOffset = checkpoint.nextOffset.max(nextOffset))

  def markInvalidRecord(checkpoint: ProjectionCheckpoint, recordOffset: Long): ProjectionCheckpoint =
    val laterInvalid = checkpoint.invalidRecordOffset.forall(recordOffset > _)
    checkpoint.copy(
      recoverySnapshotId = if laterInvalid then None else checkpoint.recoverySnapshotId,
      invalidRecordOffset = Some(checkpoint.invalidRecordOffset.getOrElse(-1L).max(recordOffset))
    )

  // The first marker after an invalid record only remembers its snapshot. A distinct later snapshot proves that
  // a full snapshot ran after the gap, so it clears the latch.
  def markSnapshotCompleted(checkpoint: ProjectionCheckpoint, markerOffset: Long, snapshotId: String): ProjectionCheckpoint =
    val invalid = checkpoint.invalidRecordOffset
    val recovers = invalid.exists(markerOffset > _) &&
      checkpoint.recoverySnapshotId.exists(_ != snapshotId) &&
      !checkpoint.lastSnapshotId.contains(snapshotId)
    val nextInvalid = if recovers then None else invalid
    val nextRecovery = nextInvalid match
      case None                                                  => None
      case Some(offset) if markerOffset <= offset                => checkpoint.recoverySnapshotId
      case Some(_) if checkpoint.lastSnapshotId.contains(snapshotId) => checkpoint.recoverySnapshotId
      case Some(_)                                               => checkpoint.recoverySnapshotId.orElse(Some(snapshotId))
    val newestMarker = checkpoint.snapshotCompletedOffset.forall(markerOffset >= _)
    checkpoint.copy(
      invalidRecordOffset = nextInvalid,
      recoverySnapshotId = nextRecovery,
      lastSnapshotId = if newestMarker then Some(snapshotId) else checkpoint.lastSnapshotId,
      snapshotCompletedOffset = Some(checkpoint.snapshotCompletedOffset.getOrElse(-1L).max(markerOffset))
    )

  def isReady(
      topicId: String,
      brokerRanges: Map[Int, BrokerRange],
      barriers: Map[Int, ProjectionBarrier],
      checkpoints: Map[Int, ProjectionCheckpoint]
  ): Boolean =
    val partitions = brokerRanges.keySet
    partitions.nonEmpty && barriers.keySet == partitions && checkpoints.keySet == partitions &&
    partitions.forall { partition =>
      val barrier = barriers(partition)
      val checkpoint = checkpoints(partition)
      val range = brokerRanges(partition)
      barrier.topicId == topicId && checkpoint.topicId == topicId &&
      checkpoint.invalidCheckpointOffset.isEmpty && checkpoint.invalidRecordOffset.isEmpty &&
      checkpoint.snapshotCompletedOffset.exists(completed =>
        checkpoint.nextOffset > completed && completed >= range.beginningOffset
      ) &&
      barrier.targetOffset <= range.endOffset && checkpoint.nextOffset == range.endOffset
    }

  private def inRange(offset: Long, range: PartitionRange): Boolean =
    offset >= range.beginningOffset && offset <= range.endOffset
