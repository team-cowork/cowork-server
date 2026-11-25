package com.cowork.applet.global.projection

class SnapshotMarkerTest extends munit.FunSuite:

  private val key = s"${SnapshotMarker.KeyPrefix}0"
  private val snapshotId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

  private def payload(source: String = "cowork-team", partition: Int = 0, topic: String = "team.member.event") =
    s"""{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"$topic","partition":$partition,"snapshotId":"$snapshotId","occurredAt":"2026-11-24T00:00:00Z","source":"$source"}"""

  test("parse / 올바른 마커 / 스냅샷 ID를 반환한다") {
    // When
    val result = SnapshotMarker.parse("team.member.event", 0, key, payload(), "cowork-team")

    // Then
    assertEquals(result, Right(snapshotId))
  }

  test("parse / 키, 토픽, 파티션, 발행자가 다른 마커 / 거부한다") {
    // Given
    val cases = Seq(
      (s"${SnapshotMarker.KeyPrefix}1", payload()),
      (key, payload(topic = "other")),
      (key, payload(partition = 1)),
      (key, payload(source = "cowork-chat")),
      (key, "not json")
    )

    // When / Then
    cases.foreach((k, v) => assert(SnapshotMarker.parse("team.member.event", 0, k, v, "cowork-team").isLeft, v))
  }
