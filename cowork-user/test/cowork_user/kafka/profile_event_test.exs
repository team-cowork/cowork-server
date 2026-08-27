defmodule CoworkUser.Kafka.ProfileEventTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.{ProfileEvent, ProfileEventPublisher}

  test "공개 프로필을 UPSERT 계약으로 매핑한다" do
    occurred_at = ~U[2026-08-26 12:34:56.123456Z]

    event =
      ProfileEvent.upsert(
        %{id: 42, name: "Snowy", nickname: "snow", github_id: "snowykte0426"},
        occurred_at
      )

    assert event == %{
             eventType: "UPSERT",
             userId: 42,
             name: "Snowy",
             nickname: "snow",
             githubId: "snowykte0426",
             version: 1,
             occurredAt: "2026-08-26T12:34:56.123456Z"
           }
  end

  test "삭제 이벤트는 식별자와 발생 시각을 보존한다" do
    assert ProfileEvent.delete(42, ~U[2026-08-26 12:34:56Z]) == %{
             eventType: "DELETE",
             userId: 42,
             name: nil,
             nickname: nil,
             githubId: nil,
             version: 1,
             occurredAt: "2026-08-26T12:34:56Z"
           }
  end

  test "snapshot은 원본 행의 수정 시각을 이벤트 순서로 사용한다" do
    event =
      ProfileEvent.upsert(%{
        id: 42,
        name: "Snowy",
        nickname: nil,
        github_id: nil,
        event_occurred_at: ~U[2026-08-26 01:02:03.123456Z]
      })

    assert event.occurredAt == "2026-08-26T01:02:03.123456Z"
  end

  test "outbox key와 payload userId는 동일한 안정 식별자를 사용한다" do
    [row] =
      ProfileEventPublisher.outbox_rows("user.profile.event", [
        %{
          id: 42,
          name: "Snowy",
          nickname: "snow",
          github_id: "snowykte0426",
          account_updated_at: ~U[2026-08-26 01:02:03.123456Z],
          profile_updated_at: ~U[2026-08-26 01:02:04.123456Z],
          profile_event_version: 8,
          profile_event_occurred_at: ~U[2026-08-26 01:02:05.123456Z]
        }
      ])

    assert row.topic == "user.profile.event"
    assert row.event_key == "42"

    assert Jason.decode!(row.payload) == %{
             "eventType" => "UPSERT",
             "userId" => 42,
             "name" => "Snowy",
             "nickname" => "snow",
             "githubId" => "snowykte0426",
             "version" => 8,
             "occurredAt" => "2026-08-26T01:02:05.123456Z"
           }
  end

  test "persisted profile event clock advances across host clock rollback" do
    current = ~U[2026-08-27 01:02:03.123456Z]

    assert {~U[2026-08-27 01:02:03.123457Z], 8} =
             ProfileEventPublisher.next_profile_event_state(
               current,
               7,
               [DateTime.add(current, -60, :second)]
             )

    future = DateTime.add(current, 10, :second)

    assert {^future, 8} =
             ProfileEventPublisher.next_profile_event_state(current, 7, [future])
  end

  test "identity transfer의 donor와 claimant 공개 상태를 각각 UPSERT로 만든다" do
    occurred_at = ~U[2026-08-27 01:02:03.123456Z]

    rows =
      ProfileEventPublisher.outbox_rows("user.profile.event", [
        %{
          id: 7,
          name: "Previous owner",
          nickname: nil,
          github_id: nil,
          account_updated_at: occurred_at,
          profile_updated_at: occurred_at,
          profile_event_version: 10,
          profile_event_occurred_at: occurred_at
        },
        %{
          id: 8,
          name: "Claimant",
          nickname: nil,
          github_id: "transferred",
          account_updated_at: occurred_at,
          profile_updated_at: occurred_at,
          profile_event_version: 11,
          profile_event_occurred_at: occurred_at
        }
      ])

    assert Enum.map(rows, & &1.event_key) == ["7", "8"]

    assert Enum.map(rows, &(Jason.decode!(&1.payload) |> Map.take(["userId", "githubId"]))) == [
             %{"userId" => 7, "githubId" => nil},
             %{"userId" => 8, "githubId" => "transferred"}
           ]
  end

  test "snapshot 완료 marker는 모든 partition에 명시 발행되도록 outbox에 적재한다" do
    snapshot_id = "a8266450-8f99-48e1-94a1-6b62f3b0741a"

    rows =
      ProfileEventPublisher.snapshot_barrier_rows(
        "user.profile.event",
        0..2,
        snapshot_id,
        ~U[2026-08-26 12:34:56.123456Z]
      )

    assert Enum.map(rows, & &1.partition_id) == [0, 1, 2]

    assert Enum.map(rows, & &1.event_key) == [
             "__cowork_projection_snapshot_complete__:0",
             "__cowork_projection_snapshot_complete__:1",
             "__cowork_projection_snapshot_complete__:2"
           ]

    Enum.each(rows, fn row ->
      assert Jason.decode!(row.payload) == %{
               "eventType" => "PROJECTION_SNAPSHOT_COMPLETED",
               "topic" => "user.profile.event",
               "partition" => row.partition_id,
               "snapshotId" => snapshot_id,
               "occurredAt" => "2026-08-26T12:34:56.123456Z",
               "source" => "cowork-user"
             }
    end)
  end
end
