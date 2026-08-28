defmodule CoworkUser.Kafka.ProjectionReplayTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.ProjectionReplay
  alias CoworkUser.Kafka.ProjectionReplayTest.RetryOnceStorage

  defmodule FakeStorage do
    def begin_replay(consumer_group, topic, partition, beginning_offset, replay_owner) do
      send(
        self(),
        {:begin_replay, consumer_group, topic, partition, beginning_offset, replay_owner}
      )

      case Process.get(:fake_storage_begin_replay_result) do
        nil -> {:ok, %{consumer_group: consumer_group, topic: topic, partition: partition}}
        result -> result
      end
    end

    def assignment_lease(consumer_group, topic, partition, replay_owner) do
      send(self(), {:assignment_lease, consumer_group, topic, partition, replay_owner})

      case Process.get(:fake_storage_assignment_lease_result) do
        nil -> {:ok, %{consumer_group: consumer_group, topic: topic, partition: partition}}
        result -> result
      end
    end

    def renew_assignment(lease) do
      send(self(), {:renew_assignment, lease})
      Process.get(:fake_storage_renew_assignment_result, :ok)
    end

    def release_assignment(lease) do
      send(self(), {:release_assignment, lease})
      Process.get(:fake_storage_release_assignment_result, :ok)
    end
  end

  defmodule FakeReadiness do
    def assignment_replay_started(consumer_group, topic, partition) do
      send(self(), {:assignment_replay_started, consumer_group, topic, partition})
      Process.get(:fake_readiness_started_result, :ok)
    end

    def assignment_replay_finished(consumer_group, topic, partition) do
      send(self(), {:assignment_replay_finished, consumer_group, topic, partition})
      Process.get(:fake_readiness_finished_result, :ok)
    end
  end

  defmodule FakeBroker do
    def resolve_offset(endpoints, topic, partition, position) do
      send(self(), {:resolve_offset, endpoints, topic, partition, position})
      Process.get(:fake_broker_resolve_offset_result, {:ok, 42})
    end
  end

  setup do
    on_exit(fn ->
      Process.delete(:fake_storage_begin_replay_result)
      Process.delete(:fake_storage_assignment_lease_result)
      Process.delete(:fake_storage_renew_assignment_result)
      Process.delete(:fake_storage_release_assignment_result)
      Process.delete(:fake_readiness_started_result)
      Process.delete(:fake_readiness_finished_result)
      Process.delete(:fake_broker_resolve_offset_result)
    end)

    :ok
  end

  defp cb_config(overrides \\ %{}) do
    Map.merge(
      %{
        consumer_group: "cowork-user.team-member",
        replay_owner: "owner-1",
        bootstrap_endpoints: [{~c"kafka", 9092}]
      },
      overrides
    )
  end

  describe "begin_assignment/6" do
    test "opens replay readiness, resolves the earliest offset and claims the assignment" do
      assert {:ok, {:begin_offset, 42}} =
               ProjectionReplay.begin_assignment(
                 cb_config(),
                 "team.member.event",
                 3,
                 FakeBroker,
                 FakeStorage,
                 FakeReadiness
               )

      assert_receive {:assignment_replay_started, "cowork-user.team-member", "team.member.event",
                       3}

      assert_receive {:resolve_offset, [{~c"kafka", 9092}], "team.member.event", 3, :earliest}

      assert_receive {:begin_replay, "cowork-user.team-member", "team.member.event", 3, 42,
                       "owner-1"}
    end

    test "propagates a readiness failure without touching the broker or storage" do
      Process.put(:fake_readiness_started_result, {:error, :readiness_unavailable})

      assert {:error, :readiness_unavailable} =
               ProjectionReplay.begin_assignment(
                 cb_config(),
                 "team.member.event",
                 3,
                 FakeBroker,
                 FakeStorage,
                 FakeReadiness
               )

      refute_receive {:resolve_offset, _endpoints, _topic, _partition, _position}
      refute_receive {:begin_replay, _consumer_group, _topic, _partition, _offset, _owner}
    end

    test "propagates a broker offset resolution failure without claiming the assignment" do
      Process.put(:fake_broker_resolve_offset_result, {:error, :leader_not_available})

      assert {:error, :leader_not_available} =
               ProjectionReplay.begin_assignment(
                 cb_config(),
                 "team.member.event",
                 3,
                 FakeBroker,
                 FakeStorage,
                 FakeReadiness
               )

      refute_receive {:begin_replay, _consumer_group, _topic, _partition, _offset, _owner}
    end

    test "retries once when the assignment lease is briefly busy, then claims it" do
      Process.put(:retry_once_storage_calls, 0)

      assert {:ok, {:begin_offset, 42}} =
               ProjectionReplay.begin_assignment(
                 cb_config(),
                 "team.member.event",
                 3,
                 FakeBroker,
                 RetryOnceStorage,
                 FakeReadiness
               )

      assert Process.get(:retry_once_storage_calls) == 2
    end
  end

  describe "assignment_lease/4" do
    test "acquires the lease and only then signals replay finished" do
      assert {:ok, %{consumer_group: "cowork-user.team-member"}} =
               ProjectionReplay.assignment_lease(
                 %{topic: "team.member.event", partition: 3},
                 cb_config(),
                 FakeStorage,
                 FakeReadiness
               )

      assert_receive {:assignment_lease, "cowork-user.team-member", "team.member.event", 3,
                       "owner-1"}

      assert_receive {:assignment_replay_finished, "cowork-user.team-member",
                       "team.member.event", 3}
    end

    test "does not announce replay finished when the lease cannot be acquired" do
      Process.put(
        :fake_storage_assignment_lease_result,
        {:error, :assignment_replay_not_initialized}
      )

      assert {:error, :assignment_replay_not_initialized} =
               ProjectionReplay.assignment_lease(
                 %{topic: "team.member.event", partition: 3},
                 cb_config(),
                 FakeStorage,
                 FakeReadiness
               )

      refute_receive {:assignment_replay_finished, _consumer_group, _topic, _partition}
    end
  end

  describe "start_assignment_heartbeat/1" do
    test "schedules a renewal message and stores the timer reference" do
      state = ProjectionReplay.start_assignment_heartbeat(%{some: :state})

      assert is_reference(state.replay_lease_heartbeat_ref)
      assert state.some == :state
    end
  end

  describe "renew_assignment/2" do
    test "re-arms the heartbeat after a successful renewal" do
      state = %{replay_lease: %{token: "t"}}

      assert {:ok, next_state} = ProjectionReplay.renew_assignment(state, FakeStorage)

      assert_receive {:renew_assignment, %{token: "t"}}
      assert is_reference(next_state.replay_lease_heartbeat_ref)
    end

    test "returns the storage error without scheduling another heartbeat" do
      Process.put(:fake_storage_renew_assignment_result, {:error, :lease_expired})
      state = %{replay_lease: %{token: "t"}}

      assert {:error, :lease_expired} = ProjectionReplay.renew_assignment(state, FakeStorage)
      refute Map.has_key?(state, :replay_lease_heartbeat_ref)
    end
  end

  describe "stop_assignment/2" do
    test "cancels a pending heartbeat timer and releases the lease" do
      timer_ref = Process.send_after(self(), :heartbeat_should_not_fire, 30)
      state = %{replay_lease: %{token: "t"}, replay_lease_heartbeat_ref: timer_ref}

      assert :ok = ProjectionReplay.stop_assignment(state, FakeStorage)

      assert_receive {:release_assignment, %{token: "t"}}
      refute_receive :heartbeat_should_not_fire, 100
    end

    test "releases the lease even when no heartbeat timer was scheduled" do
      state = %{replay_lease: %{token: "t"}}

      assert :ok = ProjectionReplay.stop_assignment(state, FakeStorage)
      assert_receive {:release_assignment, %{token: "t"}}
    end
  end

  defmodule RetryOnceStorage do
    def begin_replay(consumer_group, topic, partition, beginning_offset, replay_owner) do
      calls = Process.get(:retry_once_storage_calls, 0) + 1
      Process.put(:retry_once_storage_calls, calls)

      if calls == 1 do
        {:error, :assignment_replay_lease_busy}
      else
        {:ok,
         %{
           consumer_group: consumer_group,
           topic: topic,
           partition: partition,
           beginning_offset: beginning_offset,
           owner: replay_owner
         }}
      end
    end
  end
end
