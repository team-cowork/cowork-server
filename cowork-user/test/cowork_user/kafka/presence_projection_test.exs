defmodule CoworkUser.Kafka.PresenceProjectionTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.PresenceProjection

  defmodule RecordingStore do
    def apply(event) do
      send(self(), {:presence_applied, event})
      :ok
    end
  end

  test "presence 이벤트를 계정 상태 변경으로 정규화한다" do
    payload = %{
      "eventType" => "STATUS_CHANGED",
      "userId" => 42,
      "status" => "offline",
      "occurredAt" => "2026-08-26T12:34:56Z"
    }

    assert :ok = PresenceProjection.apply_event(payload, RecordingStore)

    assert_receive {:presence_applied,
                    %{user_id: 42, status: "offline", occurred_at: ~N[2026-08-26 12:34:56]}}
  end

  test "지원하지 않는 상태는 거부한다" do
    assert {:error, :invalid_status} =
             PresenceProjection.apply_event(
               %{
                 "eventType" => "STATUS_CHANGED",
                 "userId" => 42,
                 "status" => "away",
                 "occurredAt" => "2026-08-26T12:34:56Z"
               },
               RecordingStore
             )

    refute_receive {:presence_applied, _}
  end

  test "동일 시각의 online/offline 충돌은 전달 순서와 무관하게 offline이 이긴다" do
    occurred_at = ~N[2026-08-26 12:34:56.123456]

    assert "offline" =
             PresenceProjection.resolve_status(
               "online",
               occurred_at,
               "offline",
               occurred_at
             )

    assert "offline" =
             PresenceProjection.resolve_status(
               "offline",
               occurred_at,
               "online",
               occurred_at
             )
  end

  test "더 최신 상태만 적용하고 오래된 상태는 무시한다" do
    current_at = ~N[2026-08-26 12:34:56.123456]

    assert "online" =
             PresenceProjection.resolve_status(
               "offline",
               current_at,
               "online",
               NaiveDateTime.add(current_at, 1, :microsecond)
             )

    assert "offline" =
             PresenceProjection.resolve_status(
               "offline",
               current_at,
               "online",
               NaiveDateTime.add(current_at, -1, :microsecond)
             )
  end

  test "storage SQL uses the same deterministic offline-wins policy" do
    sql = CoworkUser.Kafka.PresenceProjection.Storage.sql_contract()

    assert sql.upsert =~ "VALUES(event_occurred_at) > event_occurred_at"
    assert sql.upsert =~ "VALUES(status) = 'offline'"
    assert sql.upsert =~ "status = 'online'"
    assert sql.apply_account =~ "presence.event_occurred_at > account.presence_updated_at"
    assert sql.apply_account =~ "presence.status = 'offline'"
    assert sql.apply_account =~ "account.status = 'online'"
    refute sql.upsert =~ "custom_status"
    refute sql.apply_account =~ "custom_status"
  end
end
