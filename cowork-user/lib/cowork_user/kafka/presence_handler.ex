defmodule CoworkUser.Kafka.PresenceHandler do
  @behaviour :brod_group_subscriber_v2

  require Logger

  alias CoworkUser.Kafka.{
    EventParser,
    PresenceProjection,
    ProjectionBarrier,
    ProjectionProcessor,
    ProjectionReplay,
    TransientSyncError
  }

  @expected_barrier_source "cowork-authorization"

  @impl :brod_group_subscriber_v2
  def init(init_info, cb_config) do
    case ProjectionReplay.assignment_lease(init_info, cb_config) do
      {:ok, replay_lease} ->
        state =
          cb_config
          |> Map.put(:topic, init_info.topic)
          |> Map.put(:partition, init_info.partition)
          |> Map.put(:replay_lease, replay_lease)
          |> ProjectionReplay.start_assignment_heartbeat()

        {:ok, state}

      {:error, reason} ->
        raise TransientSyncError,
          message: "presence assignment replay lease failed: #{inspect(reason)}"
    end
  end

  @impl :brod_group_subscriber_v2
  def handle_message({:kafka_message, offset, key, value, _ts_type, _ts, _headers}, state)
      when is_binary(value) do
    case Jason.decode(value) do
      {:ok, %{} = payload} ->
        process(payload, key, value, offset, state)

      {:ok, _other} ->
        discard(:unexpected_json_payload_type, key, value, offset, state)

      {:error, %Jason.DecodeError{position: position}} ->
        discard({:invalid_json, position}, key, value, offset, state)
    end
  end

  def handle_message(_other, _state) do
    raise TransientSyncError, message: "unexpected Kafka message shape"
  end

  @impl :brod_group_subscriber_v2
  def get_committed_offset(cb_config, topic, partition) do
    case ProjectionReplay.begin_assignment(cb_config, topic, partition) do
      {:ok, begin_offset} ->
        {:ok, begin_offset}

      {:error, reason} ->
        raise TransientSyncError,
          message: "presence assignment replay reset failed: #{inspect(reason)}"
    end
  end

  def handle_info(:renew_projection_assignment_lease, state) do
    case ProjectionReplay.renew_assignment(state) do
      {:ok, next_state} ->
        {:noreply, next_state}

      {:error, reason} ->
        raise TransientSyncError,
          message: "presence assignment lease renewal failed: #{inspect(reason)}"
    end
  end

  @impl :brod_group_subscriber_v2
  def terminate(_reason, state) do
    _ = ProjectionReplay.stop_assignment(state)
    :ok
  end

  defp process(payload, key, raw_payload, offset, state) do
    case ProjectionBarrier.parse(
           payload,
           to_string(key),
           state.topic,
           state.partition,
           @expected_barrier_source
         ) do
      {:ok, marker} ->
        ProjectionProcessor.process_barrier(
          state.replay_lease,
          offset,
          marker
        )
        |> finish(offset, state)

      :not_barrier ->
        process_presence(payload, key, raw_payload, offset, state)

      {:error, reason} ->
        discard(reason, key, raw_payload, offset, state)
    end
  rescue
    exception in TransientSyncError ->
      reraise(exception, __STACKTRACE__)

    exception ->
      raise TransientSyncError,
        message: "presence projection crashed offset=#{offset}: #{Exception.message(exception)}"
  end

  defp process_presence(payload, key, raw_payload, offset, state) do
    with {:ok, user_id} <- EventParser.positive_integer(payload, "userId", "user_id"),
         true <- to_string(key) == to_string(user_id) || {:error, {:mismatched_key, key, user_id}} do
      ProjectionProcessor.process(
        state.replay_lease,
        offset,
        %{key: key, payload: raw_payload},
        fn -> PresenceProjection.apply_event(payload) end
      )
      |> finish(offset, state)
    else
      {:error, reason} -> discard(reason, key, raw_payload, offset, state)
    end
  end

  defp finish(:ok, _offset, state), do: {:ok, :commit, state}

  defp finish({:discard, reason}, offset, state) do
    log_discard(reason, offset, state)
    {:ok, :commit, state}
  end

  defp finish({:error, {:storage, reason}}, offset, _state) do
    raise TransientSyncError,
      message: "presence projection failure offset=#{offset}: #{inspect(reason)}"
  end

  defp discard(reason, key, raw_payload, offset, state) when is_integer(offset) do
    case ProjectionProcessor.discard(
           state.replay_lease,
           offset,
           %{key: key, payload: raw_payload},
           reason
         ) do
      {:discard, _reason} ->
        log_discard(reason, offset, state)
        {:ok, :commit, state}

      {:error, {:storage, storage_reason}} ->
        raise TransientSyncError,
          message:
            "presence quarantine/checkpoint failure offset=#{offset}: #{inspect(storage_reason)}"
    end
  end

  defp log_discard(reason, offset, state) do
    Logger.warning(
      "Discarding invalid user.presence.event topic=#{state.topic} partition=#{state.partition} offset=#{inspect(offset)} reason=#{inspect(reason)}"
    )
  end
end
