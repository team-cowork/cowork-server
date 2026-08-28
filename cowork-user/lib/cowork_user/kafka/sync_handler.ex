defmodule CoworkUser.Kafka.SyncHandler do
  @behaviour :brod_group_subscriber_v2

  require Logger

  alias CoworkUser.Accounts
  alias CoworkUser.Kafka.{ActionQuarantine, TransientSyncError, UserSyncContract}

  @impl :brod_group_subscriber_v2
  def init(init_info, cb_config) do
    state =
      cb_config
      |> Map.put(:topic, init_info.topic)
      |> Map.put(:partition, init_info.partition)

    {:ok, state}
  end

  @impl :brod_group_subscriber_v2
  def handle_message(message, state) do
    case decode_payload(message) do
      {:ok, payload, record} ->
        case UserSyncContract.parse(payload, record.key) do
          {:ok, event} -> process_sync_event(event, record, state)
          {:error, reason} -> quarantine(record, reason, state)
        end

      {:invalid, record, reason} ->
        quarantine(record, reason, state)

      {:retry, reason} ->
        retry!(reason)
    end
  end

  defp process_sync_event(payload, record, state) do
    case apply_sync_event(payload, state) do
      :ok ->
        Logger.info(
          "Processed Kafka user sync event topic=#{state.topic} partition=#{state.partition} offset=#{record.offset} key=#{inspect(record.key)}"
        )

        {:ok, :commit, state}

      {:skip, reason} ->
        Logger.warning(
          "Skipped Kafka user sync event topic=#{state.topic} partition=#{state.partition} offset=#{record.offset} reason=#{inspect(reason)}"
        )

        {:ok, :commit, state}

      {:retry, reason} ->
        retry!(reason)

      {:error, reason} ->
        quarantine(record, reason, state)
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

  defp decode_payload(other), do: {:retry, {:unexpected_message, other}}

  defp apply_sync_event(payload, state) do
    processor = Map.get(state, :sync_processor, &Accounts.apply_student_event/1)

    case processor.(payload) do
      :ok -> :ok
      {:skip, reason} -> {:skip, reason}
      {:error, {:validation, reason}} -> {:error, reason}
      {:error, {:transient, reason}} -> {:retry, reason}
      {:error, reason} -> {:error, reason}
    end
  rescue
    exception -> {:retry, Exception.message(exception)}
  end

  defp quarantine(record, reason, state) do
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

    quarantine = Map.get(state, :action_quarantine, &ActionQuarantine.quarantine/1)

    case quarantine.(entry) do
      :ok ->
        Logger.warning(
          "Quarantined invalid Kafka user sync action topic=#{state.topic} partition=#{state.partition} offset=#{record.offset} reason=#{inspect(reason)}"
        )

        {:ok, :commit, state}

      {:error, storage_reason} ->
        retry!({:action_quarantine, storage_reason})
    end
  end

  defp retry!(reason) do
    raise TransientSyncError,
      message: "kafka user sync transient failure without offset commit: #{inspect(reason)}"
  end
end
