defmodule CoworkUser.Kafka.TeamMemberHandler do
  @behaviour :brod_group_subscriber_v2

  require Logger

  alias CoworkUser.Kafka.{
    EventParser,
    ProjectionBarrier,
    ProjectionCheckpoint,
    ProjectionProcessor,
    TransientSyncError
  }

  alias CoworkUser.TeamMembershipProjection

  @expected_barrier_source "cowork-team"

  @impl :brod_group_subscriber_v2
  def init(init_info, cb_config) do
    {:ok,
     cb_config |> Map.put(:topic, init_info.topic) |> Map.put(:partition, init_info.partition)}
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
    consumer_group = Map.fetch!(cb_config, :consumer_group)

    case ProjectionCheckpoint.next_offset(consumer_group, topic, partition) do
      {:ok, nil} ->
        :undefined

      {:ok, next_offset} ->
        {:ok, {:begin_offset, next_offset}}

      {:error, reason} ->
        raise TransientSyncError, message: "checkpoint read failed: #{inspect(reason)}"
    end
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
          state.consumer_group,
          state.topic,
          state.partition,
          offset,
          marker
        )
        |> finish(offset, state)

      :not_barrier ->
        process_team_member(payload, key, raw_payload, offset, state)

      {:error, reason} ->
        discard(reason, key, raw_payload, offset, state)
    end
  rescue
    exception in TransientSyncError ->
      reraise(exception, __STACKTRACE__)

    exception ->
      raise TransientSyncError,
        message:
          "team membership projection crashed offset=#{offset}: #{Exception.message(exception)}"
  end

  defp process_team_member(payload, key, raw_payload, offset, state) do
    with {:ok, expected_key} <- expected_key(payload),
         true <- to_string(key) == expected_key || {:error, {:mismatched_key, key, expected_key}} do
      ProjectionProcessor.process(
        state.consumer_group,
        state.topic,
        state.partition,
        offset,
        %{key: key, payload: raw_payload},
        fn -> TeamMembershipProjection.apply_event(payload) end
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
      message: "team membership projection failure offset=#{offset}: #{inspect(reason)}"
  end

  defp expected_key(payload) do
    with {:ok, team_id} <- EventParser.positive_integer(payload, "teamId", "team_id"),
         {:ok, user_id} <- EventParser.positive_integer(payload, "userId", "user_id") do
      {:ok, "#{team_id}:#{user_id}"}
    end
  end

  defp discard(reason, key, raw_payload, offset, state) when is_integer(offset) do
    case ProjectionProcessor.discard(
           state.consumer_group,
           state.topic,
           state.partition,
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
            "team membership quarantine/checkpoint failure offset=#{offset}: #{inspect(storage_reason)}"
    end
  end

  defp log_discard(reason, offset, state) do
    Logger.warning(
      "Discarding invalid team.member.event topic=#{state.topic} partition=#{state.partition} offset=#{inspect(offset)} reason=#{inspect(reason)}"
    )
  end
end
