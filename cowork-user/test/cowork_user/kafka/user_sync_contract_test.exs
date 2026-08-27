defmodule CoworkUser.Kafka.UserSyncContractTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.UserSyncContract

  test "accepts the authorization DataGSM contract keyed by immutable student identity" do
    payload = valid_payload()

    assert {:ok, event} = UserSyncContract.parse(payload, "70")
    assert event == payload
  end

  test "rejects key drift, unknown fields, unsupported actions and invalid field types" do
    payload = valid_payload()

    assert {:error, :invalid_user_sync_contract} = UserSyncContract.parse(payload, "71")

    assert {:error, :invalid_user_sync_contract} =
             UserSyncContract.parse(Map.put(payload, "user_id", 7), "70")

    assert {:error, :invalid_user_sync_contract} =
             UserSyncContract.parse(Map.put(payload, "event_type", "student.deleted"), "70")

    assert {:error, :invalid_user_sync_contract} =
             UserSyncContract.parse(Map.put(payload, "event_index", "0"), "70")

    assert {:error, :invalid_user_sync_contract} =
             UserSyncContract.parse(Map.put(payload, "occurred_at", "not-a-time"), "70")
  end

  test "accepts the producer's nullable and blank optional field domain" do
    payload =
      valid_payload()
      |> Map.put("student_number", -1)
      |> Map.put("major", "")
      |> Map.put("specialty", "")
      |> Map.put("github_id", "")

    assert {:ok, ^payload} = UserSyncContract.parse(payload, "70")
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
