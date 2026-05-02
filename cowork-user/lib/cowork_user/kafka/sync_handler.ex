defmodule CoworkUser.Kafka.SyncHandler do
  @behaviour :brod_group_subscriber_v2

  require Logger

  alias CoworkUser.Accounts
  alias CoworkUser.Kafka.TransientSyncError

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
      {:ok, payload, offset, key} ->
        case process_sync_event(payload) do
          :ok ->
            Logger.info(
              "Processed Kafka user sync event topic=#{state.topic} partition=#{state.partition} offset=#{offset} key=#{inspect(key)}"
            )

            {:ok, :commit, state}

          {:skip, reason} ->
            Logger.warning(
              "Skipped Kafka user sync event topic=#{state.topic} partition=#{state.partition} offset=#{offset} reason=#{inspect(reason)}"
            )

            {:ok, :commit, state}

          {:retry, reason} ->
            Logger.error(
              "Kafka user sync processing failed topic=#{state.topic} partition=#{state.partition} offset=#{offset} reason=#{inspect(reason)}"
            )

            raise TransientSyncError, message: "kafka user sync transient failure: #{inspect(reason)}"

          {:error, reason} ->
            Logger.warning(
              "Skipping invalid Kafka user sync payload topic=#{state.topic} partition=#{state.partition} offset=#{offset} reason=#{inspect(reason)}"
            )

            {:ok, :commit, state}
        end

      {:error, reason} ->
        Logger.warning(
          "Invalid Kafka user sync payload topic=#{state.topic} partition=#{state.partition} reason=#{inspect(reason)}"
        )

        {:ok, :commit, state}
    end
  end

  defp decode_payload({:kafka_message, offset, key, value, _ts_type, _ts, _headers}) when is_binary(value) do
    case Jason.decode(value) do
      {:ok, %{} = payload} -> {:ok, payload, offset, key}
      {:ok, other} -> {:error, {:unexpected_payload, other}}
      {:error, reason} -> {:error, reason}
    end
  end

  defp decode_payload(other), do: {:error, {:unexpected_message, other}}

  defp process_sync_event(payload) do
    if is_nil(payload["user_id"]) and is_nil(payload["userId"]) do
      {:skip, :missing_user_id}
    else
      case Accounts.upsert_user_from_sync_event(payload) do
        {:ok, _result} -> :ok
        {:error, {:validation, reason}} -> {:error, reason}
        {:error, {:transient, reason}} -> {:retry, reason}
        {:error, reason} -> {:retry, reason}
      end
    end
  rescue
    exception -> {:retry, Exception.message(exception)}
  end
end
