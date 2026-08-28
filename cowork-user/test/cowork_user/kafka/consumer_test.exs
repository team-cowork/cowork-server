defmodule CoworkUser.Kafka.ConsumerTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.Consumer

  defp base_state(overrides \\ %{}) do
    Map.merge(
      %{
        config: %{kafka_enabled: true},
        subscribers: %{},
        backoff_ms: 5_000,
        readiness_heartbeat_ref: nil,
        replay_generation_started: false
      },
      overrides
    )
  end

  defp alive_subscriber do
    pid =
      spawn(fn ->
        receive do
          :stop -> :ok
        end
      end)

    ref = Process.monitor(pid)
    {pid, ref}
  end

  describe "handle_info(:connect, ...) when Kafka is disabled" do
    test "leaves the state untouched and never schedules a reconnect" do
      state = base_state(%{config: %{kafka_enabled: false}})

      assert {:noreply, ^state} = Consumer.handle_info(:connect, state)
      refute_receive :connect
    end
  end

  describe "handle_info({:DOWN, ...}, ...)" do
    test "clears subscribers, notifies readiness disconnected and schedules a backoff reconnect" do
      {pid, ref} = alive_subscriber()
      heartbeat_ref = Process.send_after(self(), :heartbeat_should_not_fire, 30)

      state =
        base_state(%{
          subscribers: %{team_member: %{pid: pid, ref: ref}},
          backoff_ms: 5,
          readiness_heartbeat_ref: heartbeat_ref
        })

      assert {:noreply, next_state} =
               Consumer.handle_info({:DOWN, ref, :process, pid, :shutdown}, state)

      assert next_state.subscribers == %{}
      assert next_state.backoff_ms == 10
      assert is_nil(next_state.readiness_heartbeat_ref)
      assert_receive :connect, 1_000
      refute_receive :heartbeat_should_not_fire

      send(pid, :stop)
    end

    test "doubles the backoff, capping at the configured maximum, on repeated failures" do
      {pid, ref} = alive_subscriber()
      state = base_state(%{subscribers: %{}, backoff_ms: 8})

      assert {:noreply, next_state} =
               Consumer.handle_info({:DOWN, ref, :process, pid, :normal}, %{
                 state
                 | subscribers: %{presence: %{pid: pid, ref: ref}}
               })

      assert next_state.backoff_ms == 16
      assert_receive :connect, 1_000
    end

    test "never schedules a reconnect delay beyond the configured maximum backoff" do
      {pid, ref} = alive_subscriber()
      state = base_state(%{subscribers: %{presence: %{pid: pid, ref: ref}}, backoff_ms: 45_000})

      assert {:noreply, next_state} =
               Consumer.handle_info({:DOWN, ref, :process, pid, :normal}, state)

      assert next_state.backoff_ms == 60_000
    end

    test "ignores a DOWN message for a reference that is not a tracked subscriber" do
      state = base_state(%{subscribers: %{}})
      unrelated_ref = make_ref()

      assert {:noreply, ^state} =
               Consumer.handle_info({:DOWN, unrelated_ref, :process, self(), :normal}, state)

      refute_receive :connect
    end
  end

  describe "handle_info(:announce_projection_readiness, ...)" do
    test "reschedules the heartbeat while every subscriber stays alive" do
      {pid, ref} = alive_subscriber()
      state = base_state(%{subscribers: %{team_member: %{pid: pid, ref: ref}}})

      assert {:noreply, next_state} =
               Consumer.handle_info(:announce_projection_readiness, state)

      assert is_reference(next_state.readiness_heartbeat_ref)

      send(pid, :stop)
    end

    test "clears the heartbeat when a subscriber has died" do
      {pid, ref} = alive_subscriber()
      send(pid, :stop)
      Process.sleep(10)

      state = base_state(%{subscribers: %{team_member: %{pid: pid, ref: ref}}})

      assert {:noreply, next_state} =
               Consumer.handle_info(:announce_projection_readiness, state)

      assert is_nil(next_state.readiness_heartbeat_ref)
    end

    test "clears the heartbeat when there are no subscribers at all" do
      state = base_state(%{subscribers: %{}})

      assert {:noreply, next_state} =
               Consumer.handle_info(:announce_projection_readiness, state)

      assert is_nil(next_state.readiness_heartbeat_ref)
    end
  end

  test "handle_info/2 ignores unrecognized messages" do
    state = base_state()

    assert {:noreply, ^state} = Consumer.handle_info(:some_unrelated_message, state)
  end

  describe "handle_cast({:force_replay, reason}, ...)" do
    test "tears down active subscribers and forces an immediate reconnect" do
      {pid, ref} = alive_subscriber()

      state =
        base_state(%{
          subscribers: %{team_member: %{pid: pid, ref: ref}},
          backoff_ms: 45_000,
          replay_generation_started: true
        })

      assert {:noreply, next_state} =
               Consumer.handle_cast({:force_replay, :checkpoint_topic_uuid_mismatch}, state)

      assert next_state.subscribers == %{}
      assert next_state.backoff_ms == 5_000
      assert next_state.replay_generation_started == false
      assert is_nil(next_state.readiness_heartbeat_ref)
      assert_receive :connect, 1_000

      send(pid, :stop)
    end

    test "is a no-op when there are no subscribers to fence" do
      state = base_state(%{subscribers: %{}})

      assert {:noreply, ^state} = Consumer.handle_cast({:force_replay, :any_reason}, state)
      refute_receive :connect
    end
  end

  # NOTE: terminate/2 calls :brod.stop_client/1, which reaches into the
  # :brod_sup supervision tree. Exercising it here would depend on the :brod
  # application's runtime state rather than pure Consumer logic, so it is not
  # covered by this unit-test suite.
end
