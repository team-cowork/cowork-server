defmodule CoworkUser.Kafka.ActionQuarantineTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.ActionQuarantine

  test "preserves raw action records for deterministic manual replay" do
    entry =
      ActionQuarantine.entry(
        "cowork-user.user-sync",
        "user.data.sync",
        2,
        99,
        <<0, 255>>,
        <<255, 0, 1>>,
        {:invalid_user_sync_contract, "datagsm_student_id"}
      )

    assert entry.consumer_group == "cowork-user.user-sync"
    assert entry.topic == "user.data.sync"
    assert entry.partition == 2
    assert entry.offset == 99
    assert entry.key == <<0, 255>>
    assert entry.payload == <<255, 0, 1>>
    assert entry.reason == ~s({:invalid_user_sync_contract, "datagsm_student_id"})
  end

  test "builds a strict FAILED identity result for a recoverable operation id" do
    operation_id = "00000000-0000-4000-8000-000000000001"

    assert %{
             schemaVersion: 1,
             operationId: ^operation_id,
             status: "FAILED",
             error: %{
               code: "INVALID_USER_IDENTITY_COMMAND",
               message: "cowork-user rejected an invalid identity command"
             },
             occurredAt: occurred_at
           } = ActionQuarantine.identity_failure(operation_id)

    assert {:ok, _parsed, 0} = DateTime.from_iso8601(occurred_at)
  end
end
