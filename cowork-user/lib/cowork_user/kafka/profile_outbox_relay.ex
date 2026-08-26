defmodule CoworkUser.Kafka.ProfileOutboxRelay do
  use GenServer

  require Logger

  alias CoworkUser.Kafka.Producer
  alias CoworkUser.Repo

  @lock_name "cowork-user:kafka-outbox"
  @batch_size 100
  @relay_interval_ms 250
  @error_max_characters 8_000

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  @impl true
  def init(opts) do
    config = Keyword.fetch!(opts, :config)
    state = %{enabled: config.kafka_enabled, timer: nil}
    {:ok, schedule_relay(state, 0)}
  end

  @impl true
  def handle_info(:relay, state) do
    state = %{state | timer: nil}

    try do
      relay_once()
    rescue
      exception ->
        Logger.error("User profile outbox relay failed: #{Exception.message(exception)}")
    catch
      kind, reason ->
        Logger.error("User profile outbox relay failed: #{inspect({kind, reason})}")
    end

    {:noreply, schedule_relay(state, @relay_interval_ms)}
  end

  @doc false
  def dispatch_records([], _publish, _acknowledge, _mark_failure), do: :ok

  def dispatch_records([record | remaining], publish, acknowledge, mark_failure) do
    case publish.(record) do
      :ok ->
        case acknowledge.(record) do
          :ok -> dispatch_records(remaining, publish, acknowledge, mark_failure)
          {:error, reason} -> {:error, {:acknowledge, record.id, reason}}
        end

      {:error, reason} ->
        mark_failure.(record, reason)
        {:error, {:publish, record.id, reason}}
    end
  end

  defp relay_once do
    Repo.checkout(fn ->
      case Ecto.Adapters.SQL.query(Repo, "SELECT GET_LOCK(?, 0)", [@lock_name]) do
        {:ok, %{rows: [[1]]}} ->
          try do
            case Repo.transaction(fn ->
                   records = load_batch!()

                   dispatch_records(
                     records,
                     &publish/1,
                     &acknowledge/1,
                     &mark_failure/2
                   )
                 end) do
              {:ok, :ok} ->
                :ok

              {:ok, {:error, reason}} ->
                Logger.warning("User profile outbox paused: #{inspect(reason)}")

              {:error, reason} ->
                raise "user profile outbox transaction failed: #{inspect(reason)}"
            end
          after
            release_lock()
          end

        {:ok, _result} ->
          :busy

        {:error, reason} ->
          raise "failed to acquire user profile outbox lock: #{Exception.message(reason)}"
      end
    end)
  end

  defp load_batch! do
    result =
      Ecto.Adapters.SQL.query!(
        Repo,
        """
        SELECT id, topic, event_key, partition_id, payload
        FROM tb_kafka_outbox
        ORDER BY id ASC
        LIMIT ?
        FOR UPDATE
        """,
        [@batch_size]
      )

    Enum.map(result.rows, fn [id, topic, event_key, partition_id, payload] ->
      %{
        id: id,
        topic: topic,
        event_key: event_key,
        partition_id: partition_id,
        payload: encode_payload(payload)
      }
    end)
  end

  defp publish(record) do
    Producer.publish_encoded_sync(
      record.topic,
      record.event_key,
      record.payload,
      record.partition_id || :hash
    )
  end

  defp acknowledge(record) do
    case Ecto.Adapters.SQL.query(
           Repo,
           "DELETE FROM tb_kafka_outbox WHERE id = ?",
           [record.id]
         ) do
      {:ok, %{num_rows: 1}} -> :ok
      {:ok, result} -> {:error, {:unexpected_delete_count, result.num_rows}}
      {:error, reason} -> {:error, reason}
    end
  end

  defp mark_failure(record, reason) do
    message = reason |> inspect() |> truncate_error()

    case Ecto.Adapters.SQL.query(
           Repo,
           """
           UPDATE tb_kafka_outbox
           SET attempts = attempts + 1, last_error = ?
           WHERE id = ?
           """,
           [message, record.id]
         ) do
      {:ok, _result} ->
        :ok

      {:error, error} ->
        Logger.warning("Failed to record outbox error: #{Exception.message(error)}")
    end
  end

  defp release_lock do
    case Ecto.Adapters.SQL.query(Repo, "SELECT RELEASE_LOCK(?)", [@lock_name]) do
      {:ok, %{rows: [[1]]}} ->
        :ok

      {:ok, result} ->
        Logger.warning("User profile outbox lock was not held: #{inspect(result.rows)}")

      {:error, reason} ->
        Logger.warning("Failed to release user profile outbox lock: #{Exception.message(reason)}")
    end
  end

  defp encode_payload(payload) when is_binary(payload), do: payload
  defp encode_payload(payload), do: Jason.encode!(payload)

  defp truncate_error(message), do: String.slice(message, 0, @error_max_characters)

  defp schedule_relay(%{enabled: false} = state, _delay_ms), do: state

  defp schedule_relay(%{timer: nil} = state, delay_ms) do
    %{state | timer: Process.send_after(self(), :relay, delay_ms)}
  end
end
