defmodule CoworkUser.Kafka.Consumer do
  use GenServer

  require Logger

  @client_id :cowork_user_kafka_client
  @initial_backoff_ms 5_000
  @max_backoff_ms 60_000

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @impl true
  def init(opts) do
    config = Keyword.fetch!(opts, :config)

    state = %{
      config: config,
      subscriber_pid: nil,
      subscriber_ref: nil,
      backoff_ms: @initial_backoff_ms
    }

    if config.kafka_enabled do
      send(self(), :connect)
    else
      Logger.info("Kafka consumer is disabled.")
    end

    {:ok, state}
  end

  @impl true
  def handle_info(:connect, %{config: %{kafka_enabled: false}} = state), do: {:noreply, state}

  def handle_info(:connect, state) do
    case start_subscriber(state.config) do
      {:ok, subscriber_pid} ->
        Logger.info("Kafka consumer connected to topic #{state.config.kafka_topic} with group #{state.config.kafka_group_id}.")

        {:noreply,
         %{
           state
           | subscriber_pid: subscriber_pid,
             subscriber_ref: Process.monitor(subscriber_pid),
             backoff_ms: @initial_backoff_ms
         }}

      {:error, reason} ->
        Logger.warning("Kafka consumer connection failed: #{inspect(reason)}")
        schedule_reconnect(state.backoff_ms)

        {:noreply, %{state | backoff_ms: min(state.backoff_ms * 2, @max_backoff_ms)}}
    end
  end

  def handle_info({:DOWN, ref, :process, _pid, reason}, %{subscriber_ref: ref} = state) do
    Logger.warning("Kafka subscriber stopped: #{inspect(reason)}")
    schedule_reconnect(state.backoff_ms)

    {:noreply,
     %{state | subscriber_pid: nil, subscriber_ref: nil, backoff_ms: min(state.backoff_ms * 2, @max_backoff_ms)}}
  end

  def handle_info(_message, state), do: {:noreply, state}

  @impl true
  def terminate(_reason, state) do
    if is_pid(state.subscriber_pid) and Process.alive?(state.subscriber_pid) do
      :brod_group_subscriber_v2.stop(state.subscriber_pid)
    end

    :ok = :brod.stop_client(@client_id)
    :ok
  rescue
    _ -> :ok
  end

  defp start_subscriber(config) do
    with {:ok, _apps} <- Application.ensure_all_started(:brod),
         :ok <- ensure_client(config),
         {:ok, subscriber_pid} <- :brod.start_link_group_subscriber_v2(subscriber_config(config)) do
      {:ok, subscriber_pid}
    end
  end

  defp ensure_client(config) do
    endpoints = parse_bootstrap_servers(config.kafka_bootstrap_servers)

    case :brod.start_client(endpoints, @client_id, _ssl = []) do
      :ok -> :ok
      {:error, {:already_started, _pid}} -> :ok
      {:error, {:client_down, _client_id, _reason}} -> :ok
      other -> other
    end
  end

  defp subscriber_config(config) do
    %{
      client: @client_id,
      group_id: config.kafka_group_id,
      topics: [config.kafka_topic],
      cb_module: CoworkUser.Kafka.SyncHandler,
      init_data: %{},
      message_type: :message,
      consumer_config: [
        begin_offset: :earliest,
        prefetch_count: 100,
        offset_reset_policy: :reset_to_earliest
      ],
      group_config: [
        offset_commit_policy: :commit_to_kafka_v2,
        rejoin_delay_seconds: 2
      ]
    }
  end

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
  defp schedule_reconnect(delay_ms), do: Process.send_after(self(), :connect, delay_ms)
end
