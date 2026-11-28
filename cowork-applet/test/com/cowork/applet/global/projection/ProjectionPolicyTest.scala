package com.cowork.applet.global.projection

class ProjectionPolicyTest extends munit.FunSuite:

  private val range = PartitionRange(0, 10, 50)

  test("seekDecision / 체크포인트 없음 / 처음부터 읽는다") {
    // When
    val decision = ProjectionPolicy.seekDecision(None, range, "topic-a")

    // Then
    assertEquals(decision, SeekDecision(10, "topic-a", None))
  }

  test("seekDecision / 범위 안의 체크포인트 / 저장된 위치부터 읽는다") {
    // When
    val decision = ProjectionPolicy.seekDecision(Some(ProjectionCheckpoint(30, "topic-a")), range, "topic-a")

    // Then
    assertEquals(decision, SeekDecision(30, "topic-a", None))
  }

  test("seekDecision / 토픽이 다시 만들어짐 / 처음부터 읽고 이전 위치를 무효 표시한다") {
    // When
    val decision = ProjectionPolicy.seekDecision(Some(ProjectionCheckpoint(30, "topic-old")), range, "topic-a")

    // Then
    assertEquals(decision, SeekDecision(10, "topic-a", Some(30)))
  }

  test("seekDecision / 보존 범위를 벗어난 위치 / 처음부터 읽고 무효 표시한다") {
    // When
    val decision = ProjectionPolicy.seekDecision(Some(ProjectionCheckpoint(5, "topic-a")), range, "topic-a")

    // Then
    assertEquals(decision, SeekDecision(10, "topic-a", Some(5)))
  }

  test("ensure / 다른 토픽 세대의 체크포인트 / 스냅샷 상태를 지우고 무효로 고정한다") {
    // Given
    val stored = ProjectionCheckpoint(30, "topic-old", snapshotCompletedOffset = Some(20), lastSnapshotId = Some("s"))

    // When
    val ensured = ProjectionPolicy.ensure(Some(stored), SeekDecision(10, "topic-a", Some(30)))

    // Then
    assertEquals(ensured, ProjectionCheckpoint(30, "topic-a", invalidCheckpointOffset = Some(30)))
  }

  test("barrier / 같은 토픽 / 더 큰 목표 위치를 유지한다") {
    // When
    val kept = ProjectionPolicy.barrier(Some(ProjectionBarrier("topic-a", 80)), "topic-a", 50)
    val replaced = ProjectionPolicy.barrier(Some(ProjectionBarrier("topic-old", 80)), "topic-a", 50)

    // Then
    assertEquals(kept, ProjectionBarrier("topic-a", 80))
    assertEquals(replaced, ProjectionBarrier("topic-a", 50))
  }

  test("markSnapshotCompleted / 잘못된 레코드 뒤 첫 마커 / 복구 후보만 기록하고 고정을 유지한다") {
    // Given
    val latched = ProjectionCheckpoint(21, "t", snapshotCompletedOffset = Some(5), invalidRecordOffset = Some(20), lastSnapshotId = Some("s0"))

    // When
    val afterFirst = ProjectionPolicy.markSnapshotCompleted(latched, 30, "s1")

    // Then
    assertEquals(afterFirst.invalidRecordOffset, Some(20L))
    assertEquals(afterFirst.recoverySnapshotId, Some("s1"))
    assertEquals(afterFirst.lastSnapshotId, Some("s1"))
  }

  test("markSnapshotCompleted / 다른 스냅샷의 다음 마커 / 고정을 해제한다") {
    // Given
    val latched = ProjectionCheckpoint(21, "t", snapshotCompletedOffset = Some(5), invalidRecordOffset = Some(20), lastSnapshotId = Some("s0"))
    val afterFirst = ProjectionPolicy.markSnapshotCompleted(latched, 30, "s1")

    // When
    val sameAgain = ProjectionPolicy.markSnapshotCompleted(afterFirst, 31, "s1")
    val afterSecond = ProjectionPolicy.markSnapshotCompleted(afterFirst, 40, "s2")

    // Then
    assertEquals(sameAgain.invalidRecordOffset, Some(20L))
    assertEquals(afterSecond.invalidRecordOffset, None)
    assertEquals(afterSecond.recoverySnapshotId, None)
    assertEquals(afterSecond.snapshotCompletedOffset, Some(40L))
  }

  test("markInvalidRecord / 더 뒤의 잘못된 레코드 / 복구 후보를 지운다") {
    // Given
    val checkpoint = ProjectionCheckpoint(31, "t", invalidRecordOffset = Some(20), recoverySnapshotId = Some("s1"))

    // When
    val marked = ProjectionPolicy.markInvalidRecord(checkpoint, 25)

    // Then
    assertEquals(marked.invalidRecordOffset, Some(25L))
    assertEquals(marked.recoverySnapshotId, None)
  }

  test("isReady / 스냅샷 완료 후 high-watermark까지 도달 / 준비 상태다") {
    // Given
    val ranges = Map(0 -> BrokerRange(0, 50))
    val barriers = Map(0 -> ProjectionBarrier("t", 50))
    val checkpoints = Map(0 -> ProjectionCheckpoint(50, "t", snapshotCompletedOffset = Some(40)))

    // When / Then
    assert(ProjectionPolicy.isReady("t", ranges, barriers, checkpoints))
  }

  test("isReady / 따라잡는 중, 스냅샷 없음, 잘못된 레코드, 토픽 불일치 / 준비되지 않았다") {
    // Given
    val ranges = Map(0 -> BrokerRange(0, 50))
    val barriers = Map(0 -> ProjectionBarrier("t", 50))
    val ready = ProjectionCheckpoint(50, "t", snapshotCompletedOffset = Some(40))
    val cases = Seq(
      ready.copy(nextOffset = 45),
      ready.copy(snapshotCompletedOffset = None),
      ready.copy(invalidRecordOffset = Some(42)),
      ready.copy(invalidCheckpointOffset = Some(3)),
      ready.copy(topicId = "old")
    )

    // When / Then
    cases.foreach(checkpoint => assert(!ProjectionPolicy.isReady("t", ranges, barriers, Map(0 -> checkpoint)), checkpoint.toString))
    assert(!ProjectionPolicy.isReady("t", ranges, Map.empty, Map(0 -> ready)))
  }
