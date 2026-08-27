defmodule CoworkUser.Kafka.SyncHandlerTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.{SyncHandler, TransientSyncError}

  test "quarantines and commits a sync action whose key does not match student identity" do
    test_pid = self()

    state =
      state(%{
        action_quarantine: fn entry ->
          send(test_pid, {:quarantined, entry})
          :ok
        end
      })

    assert {:ok, :commit, ^state} =
             SyncHandler.handle_message(message(valid_payload(), "71"), state)

    assert_receive {:quarantined, entry}
    assert entry.key == "71"
    assert entry.reason == ":invalid_user_sync_contract"
  end

  test "applies a valid contract before committing" do
    test_pid = self()

    state =
      state(%{
        sync_processor: fn event ->
          send(test_pid, {:applied, event})
          :ok
        end
      })

    assert {:ok, :commit, ^state} =
             SyncHandler.handle_message(message(valid_payload(), "70"), state)

    assert_receive {:applied, %{"datagsm_student_id" => 70}}
  end

  test "does not commit when sync quarantine storage fails" do
    state =
      state(%{
        action_quarantine: fn _entry -> {:error, {:storage, :database_down}} end
      })

    assert_raise TransientSyncError, fn ->
      SyncHandler.handle_message(message(valid_payload(), "71"), state)
    end
  end

  defp state(overrides) do
    Map.merge(
      %{
        topic: "user.data.sync",
        partition: 2,
        consumer_group: "cowork-user"
      },
      overrides
    )
  end

  defp message(payload, key) do
    {:kafka_message, 23, key, Jason.encode!(payload), :create, 0, []}
  end

  defp valid_payload do
    %{
      "event_type" => "student.updated",
      "event_id" => "evt-1",
      "event_index" => 0,
      "occurred_at" => "2026-08-27T01:02:03.123456Z",
      "email" => "user@example.com",
      "name" => "User",
      "sex" => "MAN",
      "student_role" => "GENERAL_STUDENT",
      "student_number" => 2105,
      "major" => "SW_DEVELOPMENT",
      "specialty" => nil,
      "github_id" => nil,
      "datagsm_student_id" => 70
    }
  end
end
