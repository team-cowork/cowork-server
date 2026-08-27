defmodule CoworkUser.Kafka.IdentityCommandHandler do
  @behaviour :brod_group_subscriber_v2

  require Logger

  alias CoworkUser.Kafka.{ActionQuarantine, IdentityCommand, TransientSyncError}

  @impl :brod_group_subscriber_v2
  def init(init_info, cb_config) do
    {:ok,
     cb_config
     |> Map.put(:topic, init_info.topic)
     |> Map.put(:partition, init_info.partition)}
  end

  @impl :brod_group_subscriber_v2
  def handle_message(message, state) do
    case decode_payload(message) do
      {:ok, payload, record} -> process(payload, record, state)
      {:invalid, record, reason} -> quarantine(record, nil, reason, state)
      {:retry, reason} -> retry!(reason)
    end
  end

  defp decode_payload({:kafka_message, offset, key, value, _ts_type, _ts, _headers})
       when is_binary(value) do
    record = %{offset: offset, key: key, payload: value}

    case Jason.decode(value) do
      {:ok, %{} = payload} -> {:ok, payload, record}
      {:ok, _other} -> {:invalid, record, :unexpected_payload}
      {:error, reason} -> {:invalid, record, {:invalid_json, reason}}
    end
  end

  defp decode_payload({:kafka_message, offset, key, value, _ts_type, _ts, _headers}) do
    {:invalid, %{offset: offset, key: key, payload: value}, :non_binary_payload}
  end

  defp decode_payload(_other), do: {:retry, :unexpected_message}

  defp process(payload, record, state) do
    processor = Map.get(state, :identity_processor, &IdentityCommand.process/3)

    case processor.(payload, record.key, state.result_topic) do
      :ok -> commit(record, state)
      {:reject, operation_id, reason} -> quarantine(record, operation_id, reason, state)
      {:retry, reason} -> retry!(reason)
    end
  end

  defp quarantine(record, operation_id, reason, state) do
    entry =
      ActionQuarantine.entry(
        state.consumer_group,
        state.topic,
        state.partition,
        record.offset,
        record.key,
        record.payload,
        reason
      )

    quarantine =
      Map.get(state, :action_quarantine, &ActionQuarantine.quarantine_identity/3)

    case quarantine.(entry, operation_id, state.result_topic) do
      :ok ->
        Logger.warning(
          "Quarantined invalid user identity command topic=#{state.topic} partition=#{state.partition} offset=#{record.offset} operation_id=#{inspect(operation_id)} reason=#{inspect(reason)}"
        )

        {:ok, :commit, state}

      {:error, storage_reason} ->
        retry!({:action_quarantine, storage_reason})
    end
  end

  defp commit(record, state) do
    Logger.info(
      "Committed user identity command topic=#{state.topic} partition=#{state.partition} offset=#{record.offset}"
    )

    {:ok, :commit, state}
  end

  defp retry!(reason) do
    raise TransientSyncError,
      message: "user identity command transient failure without offset commit: #{inspect(reason)}"
  end
end
