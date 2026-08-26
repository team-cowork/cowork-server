defmodule CoworkUser.Kafka.Producer do
  use GenServer

  require Logger

  @client_id :cowork_user_profile_producer
  @initial_backoff_ms 5_000
  @max_backoff_ms 60_000
  @retry_base_ms 1_000
  @max_retry_delay_ms 10_000
  @sync_publish_timeout_ms 10_000

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  def publish(topic, key, payload) do
    case Process.whereis(__MODULE__) do
      nil -> {:error, :producer_not_started}
      _pid -> GenServer.cast(__MODULE__, {:publish, topic, to_string(key), payload, 1})
    end
  end

  def publish_encoded_sync(topic, key, payload) when is_binary(payload) do
    publish_encoded_sync(topic, key, payload, :hash)
  end

  def publish_encoded_sync(topic, key, payload, partition)
      when is_binary(payload) and
             (partition == :hash or (is_integer(partition) and partition >= 0)) do
    case Process.whereis(__MODULE__) do
      nil ->
        {:error, :producer_not_started}

      _pid ->
        GenServer.call(
          __MODULE__,
          {:publish_encoded_sync, topic, to_string(key), payload, partition},
          @sync_publish_timeout_ms
        )
    end
  end

  def partition_count(topic) when is_binary(topic) do
    case Process.whereis(__MODULE__) do
      nil -> {:error, :producer_not_started}
      _pid -> GenServer.call(__MODULE__, {:partition_count, topic}, @sync_publish_timeout_ms)
    end
  end

  @impl true
  def init(opts) do
    config = Keyword.fetch!(opts, :config)
    initial_backoff_ms = Keyword.get(opts, :initial_backoff_ms, @initial_backoff_ms)

    state = %{
      enabled: config.kafka_enabled,
      connected: false,
      bootstrap_servers: config.kafka_bootstrap_servers,
      broker: Keyword.get(opts, :broker, :brod),
      client_ref: nil,
      reconnect_timer: nil,
      initial_backoff_ms: initial_backoff_ms,
      max_backoff_ms: Keyword.get(opts, :max_backoff_ms, @max_backoff_ms),
      backoff_ms: initial_backoff_ms,
      retry_base_ms: Keyword.get(opts, :retry_base_ms, @retry_base_ms)
    }

    state = if state.enabled, do: schedule_connect(state, 0), else: state
    {:ok, state}
  end

  @impl true
  def handle_cast({:publish, _topic, _key, _payload, _attempt}, %{enabled: false} = state),
    do: {:noreply, state}

  def handle_cast({:publish, topic, key, payload, attempt}, %{connected: false} = state) do
    state = schedule_connect(state, 0)

    retry_publish(
      topic,
      key,
      payload,
      attempt,
      :producer_disconnected,
      state
    )
  end

  def handle_cast({:publish, topic, key, payload, attempt}, state) do
    case produce(state.broker, topic, key, payload) do
      :ok ->
        {:noreply, state}

      {:error, reason} ->
        state = if client_down?(reason), do: mark_disconnected(state), else: state
        retry_publish(topic, key, payload, attempt, reason, state)
    end
  end

  @impl true
  def handle_call(
        {:publish_encoded_sync, _topic, _key, _payload, _partition},
        _from,
        %{enabled: false} = state
      ) do
    {:reply, {:error, :producer_disabled}, state}
  end

  def handle_call(
        {:publish_encoded_sync, _topic, _key, _payload, _partition},
        _from,
        %{connected: false} = state
      ) do
    {:reply, {:error, :producer_disconnected}, schedule_connect(state, 0)}
  end

  def handle_call({:publish_encoded_sync, topic, key, payload, partition}, _from, state) do
    case produce_encoded(state.broker, topic, partition, key, payload) do
      :ok ->
        {:reply, :ok, state}

      {:error, reason} ->
        state = if client_down?(reason), do: mark_disconnected(state), else: state
        {:reply, {:error, reason}, state}
    end
  end

  def handle_call({:partition_count, _topic}, _from, %{enabled: false} = state),
    do: {:reply, {:error, :producer_disabled}, state}

  def handle_call({:partition_count, _topic}, _from, %{connected: false} = state),
    do: {:reply, {:error, :producer_disconnected}, schedule_connect(state, 0)}

  def handle_call({:partition_count, topic}, _from, state) do
    {:reply, get_partitions_count(state.broker, topic), state}
  end

  @impl true
  def handle_info({:retry, topic, key, payload, attempt}, state) do
    handle_cast({:publish, topic, key, payload, attempt}, state)
  end

  def handle_info(:connect, %{enabled: true} = state) do
    state = %{state | reconnect_timer: nil}

    case connect(state) do
      :ok ->
        Logger.info("Kafka profile producer connected.")

        state =
          state
          |> Map.merge(%{connected: true, backoff_ms: state.initial_backoff_ms})
          |> monitor_client()

        {:noreply, state}

      {:error, reason} ->
        Logger.warning("Kafka profile producer connection failed: #{inspect(reason)}")

        {:noreply,
         state
         |> Map.put(:connected, false)
         |> schedule_connect(state.backoff_ms)
         |> Map.update!(:backoff_ms, &min(&1 * 2, state.max_backoff_ms))}
    end
  end

  def handle_info(:connect, state), do: {:noreply, %{state | reconnect_timer: nil}}

  def handle_info({:DOWN, ref, :process, _pid, reason}, %{client_ref: ref} = state) do
    Logger.warning("Kafka profile producer client stopped: #{inspect(reason)}")

    state =
      state
      |> Map.merge(%{client_ref: nil, connected: false})
      |> schedule_connect(state.backoff_ms)
      |> Map.update!(:backoff_ms, &min(&1 * 2, state.max_backoff_ms))

    {:noreply, state}
  end

  def handle_info({:DOWN, _ref, :process, _pid, _reason}, state), do: {:noreply, state}

  @impl true
  def terminate(_reason, state) do
    demonitor_client(state.client_ref)
    state.broker.stop_client(@client_id)
    :ok
  catch
    _, _ -> :ok
  end

  defp connect(state) do
    with {:ok, _apps} <- ensure_broker_started(state.broker),
         :ok <- ensure_client(state) do
      :ok
    end
  catch
    kind, reason -> {:error, {kind, reason}}
  end

  defp ensure_broker_started(:brod), do: Application.ensure_all_started(:brod)
  defp ensure_broker_started(broker), do: broker.ensure_started()

  defp ensure_client(state) do
    endpoints = parse_bootstrap_servers(state.bootstrap_servers)

    options = [
      auto_start_producers: true,
      default_producer_config: [required_acks: -1, ack_timeout: 5_000]
    ]

    case state.broker.start_client(endpoints, @client_id, options) do
      :ok -> :ok
      {:error, {:already_started, _pid}} -> :ok
      {:error, :already_present} -> restart_stale_client(state.broker, endpoints, options)
      other -> other
    end
  end

  defp restart_stale_client(broker, endpoints, options) do
    _ = broker.stop_client(@client_id)

    case broker.start_client(endpoints, @client_id, options) do
      :ok -> :ok
      {:error, {:already_started, _pid}} -> :ok
      other -> other
    end
  end

  defp produce(broker, topic, key, payload) do
    produce_encoded(broker, topic, :hash, key, Jason.encode!(payload))
  end

  defp produce_encoded(broker, topic, partition, key, payload) do
    broker.produce_sync(@client_id, topic, partition, key, payload)
  rescue
    exception -> {:error, {:exception, Exception.message(exception)}}
  catch
    kind, reason -> {:error, {kind, reason}}
  end

  defp get_partitions_count(broker, topic) do
    broker.get_partitions_count(@client_id, topic)
  rescue
    exception -> {:error, {:exception, Exception.message(exception)}}
  catch
    kind, reason -> {:error, {kind, reason}}
  end

  defp retry_publish(topic, key, payload, attempt, reason, state) do
    log_retry(topic, key, attempt, reason)

    Process.send_after(
      self(),
      {:retry, topic, key, payload, attempt + 1},
      retry_delay(state, attempt)
    )

    {:noreply, state}
  end

  defp log_retry(topic, key, attempt, reason) when rem(attempt, 10) == 0 do
    Logger.error(
      "Kafka profile event publish is still retrying topic=#{topic} key=#{key} attempt=#{attempt} reason=#{inspect(reason)}"
    )
  end

  defp log_retry(topic, key, attempt, reason) do
    Logger.warning(
      "Kafka profile event publish failed; retrying topic=#{topic} key=#{key} attempt=#{attempt} reason=#{inspect(reason)}"
    )
  end

  defp mark_disconnected(state) do
    state
    |> Map.put(:connected, false)
    |> schedule_connect(0)
  end

  defp monitor_client(state) do
    demonitor_client(state.client_ref)

    case Process.whereis(@client_id) do
      pid when is_pid(pid) -> %{state | client_ref: Process.monitor(pid)}
      nil -> %{state | client_ref: nil}
    end
  end

  defp demonitor_client(nil), do: :ok
  defp demonitor_client(ref), do: Process.demonitor(ref, [:flush])

  defp schedule_connect(%{reconnect_timer: nil} = state, delay_ms) do
    %{state | reconnect_timer: Process.send_after(self(), :connect, delay_ms)}
  end

  defp schedule_connect(state, _delay_ms), do: state

  defp parse_bootstrap_servers(bootstrap_servers) do
    bootstrap_servers
    |> String.split(",", trim: true)
    |> Enum.map(&String.trim/1)
    |> Enum.map(fn endpoint ->
      case String.split(endpoint, ":", parts: 2) do
        [host, port] -> {String.to_charlist(host), String.to_integer(port)}
        [host] -> {String.to_charlist(host), 9092}
      end
    end)
  end

  defp client_down?(:client_down), do: true
  defp client_down?({:client_down, _reason}), do: true
  defp client_down?({:exit, :noproc}), do: true
  defp client_down?({:exit, {:noproc, _details}}), do: true
  defp client_down?(_reason), do: false

  defp retry_delay(state, attempt) do
    min(attempt * state.retry_base_ms, @max_retry_delay_ms)
  end
end
