defmodule CoworkUser.Kafka.ProjectionReplay do
  alias CoworkUser.Kafka.{ProjectionCheckpoint, ProjectionReadiness}

  @claim_retry_ms 250
  @claim_timeout_ms 10_000
  @lease_heartbeat_ms 5_000

  def begin_assignment(
        cb_config,
        topic,
        partition,
        broker \\ :brod,
        storage \\ ProjectionCheckpoint,
        readiness \\ ProjectionReadiness
      ) do
    consumer_group = Map.fetch!(cb_config, :consumer_group)
    replay_owner = Map.fetch!(cb_config, :replay_owner)
    endpoints = Map.fetch!(cb_config, :bootstrap_endpoints)

    with :ok <- readiness.assignment_replay_started(consumer_group, topic, partition),
         {:ok, beginning_offset} <-
           broker.resolve_offset(endpoints, topic, partition, :earliest),
         {:ok, _lease} <-
           claim_assignment(
             storage,
             consumer_group,
             topic,
             partition,
             beginning_offset,
             replay_owner,
             System.monotonic_time(:millisecond) + @claim_timeout_ms
           ),
         do: {:ok, {:begin_offset, beginning_offset}}
  end

  def assignment_lease(
        init_info,
        cb_config,
        storage \\ ProjectionCheckpoint,
        readiness \\ ProjectionReadiness
      ) do
    consumer_group = Map.fetch!(cb_config, :consumer_group)
    topic = Map.fetch!(init_info, :topic)
    partition = Map.fetch!(init_info, :partition)

    with {:ok, lease} <-
           storage.assignment_lease(
             consumer_group,
             topic,
             partition,
             Map.fetch!(cb_config, :replay_owner)
           ),
         :ok <- readiness.assignment_replay_finished(consumer_group, topic, partition) do
      {:ok, lease}
    end
  end

  def start_assignment_heartbeat(state) when is_map(state) do
    Map.put(
      state,
      :replay_lease_heartbeat_ref,
      Process.send_after(self(), :renew_projection_assignment_lease, @lease_heartbeat_ms)
    )
  end

  def renew_assignment(state, storage \\ ProjectionCheckpoint)
      when is_map(state) do
    case storage.renew_assignment(Map.fetch!(state, :replay_lease)) do
      :ok -> {:ok, start_assignment_heartbeat(state)}
      {:error, reason} -> {:error, reason}
    end
  end

  def stop_assignment(state, storage \\ ProjectionCheckpoint) when is_map(state) do
    case Map.get(state, :replay_lease_heartbeat_ref) do
      reference when is_reference(reference) ->
        Process.cancel_timer(reference, async: true, info: false)

      _missing ->
        :ok
    end

    storage.release_assignment(Map.fetch!(state, :replay_lease))
  end

  defp claim_assignment(
         storage,
         consumer_group,
         topic,
         partition,
         beginning_offset,
         replay_owner,
         deadline
       ) do
    case storage.begin_replay(
           consumer_group,
           topic,
           partition,
           beginning_offset,
           replay_owner
         ) do
      {:error, :assignment_replay_lease_busy} = busy ->
        if System.monotonic_time(:millisecond) < deadline do
          Process.sleep(@claim_retry_ms)

          claim_assignment(
            storage,
            consumer_group,
            topic,
            partition,
            beginning_offset,
            replay_owner,
            deadline
          )
        else
          busy
        end

      result ->
        result
    end
  end
end
