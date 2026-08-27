defmodule CoworkUser.Kafka.PresenceHandlerTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.{PresenceHandler, TransientSyncError}

  # NOTE: `handle_message/2`'s decode-success branches (barrier, valid presence
  # event, and every discard path) all delegate to `CoworkUser.Kafka.ProjectionProcessor`
  # with no injection seam (unlike `SyncHandler`/`IdentityCommandHandler`, which accept
  # a processor/quarantine function via `state`). Exercising those branches would call
  # `CoworkUser.Repo.transaction/1` against a Repo that is never started in this pure
  # unit-test suite, crashing the test process instead of failing it cleanly. Only the
  # message-shape guard below is reachable without a live database.
  describe "handle_message/2 with an unrecognized message shape" do
    test "raises a transient sync error without touching the projection pipeline" do
      state = %{topic: "user.presence.event", partition: 0}

      assert_raise TransientSyncError, "unexpected Kafka message shape", fn ->
        PresenceHandler.handle_message(:not_a_kafka_message, state)
      end
    end

    test "raises for a kafka_message tuple carrying a non-binary value" do
      state = %{topic: "user.presence.event", partition: 0}
      message = {:kafka_message, 9, "42", %{unexpected: :shape}, :create, 0, []}

      assert_raise TransientSyncError, "unexpected Kafka message shape", fn ->
        PresenceHandler.handle_message(message, state)
      end
    end
  end
end
