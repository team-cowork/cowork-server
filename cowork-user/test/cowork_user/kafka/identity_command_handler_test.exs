defmodule CoworkUser.Kafka.IdentityCommandHandlerTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.{IdentityCommandHandler, TransientSyncError}

  test "commits a terminal rejection only after durable quarantine accepts it" do
    operation_id = "abcdefab-cdef-4abc-8def-abcdefabcdef"
    test_pid = self()

    state =
      state(%{
        identity_processor: fn _payload, _key, _topic ->
          {:reject, operation_id, :invalid_user_identity_command}
        end,
        action_quarantine: fn entry, correlated_operation_id, result_topic ->
          send(test_pid, {:quarantined, entry, correlated_operation_id, result_topic})
          :ok
        end
      })

    assert {:ok, :commit, ^state} =
             IdentityCommandHandler.handle_message(message(~s({"schemaVersion":999})), state)

    assert_receive {:quarantined, entry, ^operation_id, "user.identity.command-result"}
    assert entry.offset == 17
    assert entry.key == "7"
    assert entry.payload == ~s({"schemaVersion":999})
  end

  test "commits uncorrelated malformed JSON after quarantine" do
    test_pid = self()

    state =
      state(%{
        action_quarantine: fn entry, operation_id, _result_topic ->
          send(test_pid, {:quarantined, entry, operation_id})
          :ok
        end
      })

    assert {:ok, :commit, ^state} =
             IdentityCommandHandler.handle_message(message("not-json"), state)

    assert_receive {:quarantined, %{payload: "not-json"}, nil}
  end

  test "does not commit when durable quarantine storage fails" do
    state =
      state(%{
        identity_processor: fn _payload, _key, _topic ->
          {:reject, nil, :invalid_user_identity_command}
        end,
        action_quarantine: fn _entry, _operation_id, _result_topic ->
          {:error, {:storage, :database_down}}
        end
      })

    assert_raise TransientSyncError, fn ->
      IdentityCommandHandler.handle_message(message("{}"), state)
    end
  end

  defp state(overrides) do
    Map.merge(
      %{
        topic: "user.identity.command",
        partition: 1,
        consumer_group: "cowork-user.user-identity-command",
        result_topic: "user.identity.command-result"
      },
      overrides
    )
  end

  defp message(payload) do
    {:kafka_message, 17, "7", payload, :create, 0, []}
  end
end
