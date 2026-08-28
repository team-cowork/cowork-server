defmodule CoworkUser.Kafka.ProjectionBarrierTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.ProjectionBarrier

  @snapshot_id "a8266450-8f99-48e1-94a1-6b62f3b0741a"

  test "공통 snapshot 완료 marker 계약을 검증한다" do
    payload = %{
      "eventType" => "PROJECTION_SNAPSHOT_COMPLETED",
      "topic" => "team.member.event",
      "partition" => 2,
      "snapshotId" => @snapshot_id,
      "occurredAt" => "2026-08-26T12:34:56.123456Z",
      "source" => "cowork-team"
    }

    assert {:ok, marker} =
             ProjectionBarrier.parse(
               payload,
               "__cowork_projection_snapshot_complete__:2",
               "team.member.event",
               2,
               "cowork-team"
             )

    assert marker.snapshot_id == @snapshot_id
    assert marker.source == "cowork-team"
    assert marker.occurred_at == ~U[2026-08-26 12:34:56.123456Z]
  end

  test "marker key/topic/partition/source가 실제 record와 다르면 격리 대상으로 판정한다" do
    payload = %{
      "eventType" => "PROJECTION_SNAPSHOT_COMPLETED",
      "topic" => "team.member.event",
      "partition" => 0,
      "snapshotId" => @snapshot_id,
      "occurredAt" => "2026-08-26T12:34:56Z",
      "source" => "cowork-team"
    }

    assert {:error, :invalid_barrier_key} =
             ProjectionBarrier.parse(
               payload,
               "__cowork_projection_snapshot_complete__:1",
               "team.member.event",
               0,
               "cowork-team"
             )

    assert {:error, :invalid_barrier_source} =
             ProjectionBarrier.parse(
               payload,
               "__cowork_projection_snapshot_complete__:0",
               "team.member.event",
               0,
               "cowork-authorization"
             )
  end

  test "일반 상태 이벤트는 marker가 아니다" do
    assert :not_barrier =
             ProjectionBarrier.parse(
               %{"eventType" => "UPSERT"},
               "10:20",
               "team.member.event",
               0,
               "cowork-team"
             )
  end

  test "모든 partition marker가 checkpoint 이전에 관측되어야 ready 조건을 만족한다" do
    partition0 = {"cowork-user.team-member", "team.member.event", 0}
    partition1 = {"cowork-user.team-member", "team.member.event", 1}

    barriers = %{
      partition0 => %{beginning_offset: 3, end_offset: 9},
      partition1 => %{beginning_offset: 5, end_offset: 12}
    }

    checkpoints = %{partition0 => 10, partition1 => 13}

    refute ProjectionBarrier.markers_satisfied?(barriers, checkpoints, %{partition0 => 8})

    assert ProjectionBarrier.markers_satisfied?(barriers, checkpoints, %{
             partition0 => 8,
             partition1 => 12
           })
  end

  test "retention 범위 밖의 과거 marker는 재생 가능한 것으로 보지 않는다" do
    assert ProjectionBarrier.marker_replayable?(7, 5, 9)
    refute ProjectionBarrier.marker_replayable?(4, 5, 9)
    refute ProjectionBarrier.marker_replayable?(9, 5, 9)
  end
end
