defmodule CoworkUser.Kafka.Consumer do
  use GenServer

  require Logger

  alias CoworkUser.Kafka.{ProjectionCheckpoint, ProjectionReadiness}

  @client_id :cowork_user_kafka_consumers
  @initial_backoff_ms 5_000
  @max_backoff_ms 60_000
  @readiness_heartbeat_ms 1_000

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  def force_replay(reason), do: GenServer.cast(__MODULE__, {:force_replay, reason})

  @impl true
  def init(opts) do
    config = Keyword.fetch!(opts, :config)

    state = %{
      config: config,
      subscribers: %{},
      backoff_ms: @initial_backoff_ms,
      readiness_heartbeat_ref: nil,
      replay_generation_started: false
    }

    if config.kafka_enabled do
      notify_projection_readiness(:disconnected)
      send(self(), :connect)
    else
      Logger.info("Kafka consumers are disabled.")
    end

    {:ok, state}
  end

  @impl true
  def handle_info(:connect, %{config: %{kafka_enabled: false}} = state), do: {:noreply, state}

  def handle_info(:connect, state) do
    case ensure_replay_generation(state) do
      {:ok, generation_state} ->
        connect_subscribers(generation_state)

      {:error, reason} ->
        connection_failed(state, {:replay_generation, reason})
    end
  end

  def handle_info({:DOWN, ref, :process, _pid, reason}, state) do
    if Enum.any?(state.subscribers, fn {_name, subscriber} -> subscriber.ref == ref end) do
      cancel_readiness_heartbeat(state.readiness_heartbeat_ref)
      notify_projection_readiness(:disconnected)
      stop_subscribers(state.subscribers)
      Logger.warning("Kafka subscriber stopped: #{inspect(reason)}")
      schedule_reconnect(state.backoff_ms)

      {:noreply,
       %{
         state
         | subscribers: %{},
           backoff_ms: min(state.backoff_ms * 2, @max_backoff_ms),
           readiness_heartbeat_ref: nil
       }}
    else
      {:noreply, state}
    end
  end

  def handle_info(:announce_projection_readiness, state) do
    if subscribers_healthy?(state.subscribers) do
      notify_projection_readiness(:connected)

      {:noreply, %{state | readiness_heartbeat_ref: schedule_readiness_heartbeat()}}
    else
      notify_projection_readiness(:disconnected)
      {:noreply, %{state | readiness_heartbeat_ref: nil}}
    end
  end

  def handle_info(_message, state), do: {:noreply, state}

  @impl true
  def handle_cast({:force_replay, reason}, state) when map_size(state.subscribers) > 0 do
    Logger.warning("Restarting Kafka subscribers for a fenced full replay: #{inspect(reason)}")
    cancel_readiness_heartbeat(state.readiness_heartbeat_ref)
    notify_projection_readiness(:disconnected)
    stop_subscribers(state.subscribers)
    send(self(), :connect)

    {:noreply,
     %{
       state
       | subscribers: %{},
         readiness_heartbeat_ref: nil,
         backoff_ms: @initial_backoff_ms,
         replay_generation_started: false
     }}
  end

  def handle_cast({:force_replay, _reason}, state), do: {:noreply, state}

  @impl true
  def terminate(_reason, state) do
    cancel_readiness_heartbeat(state.readiness_heartbeat_ref)
    if state.config.kafka_enabled, do: notify_projection_readiness(:disconnected)
    stop_subscribers(state.subscribers)
    :brod.stop_client(@client_id)
    :ok
  rescue
    _ -> :ok
  end

  defp start_subscribers(config) do
    with {:ok, _apps} <- Application.ensure_all_started(:brod),
         :ok <- ensure_client(config) do
      consumer_specs(config)
      |> Enum.reduce_while({:ok, %{}}, fn {name, topic, group_id, handler, projection?,
                                           handler_data},
                                          {:ok, started} ->
        case :brod.start_link_group_subscriber_v2(
               subscriber_config(
                 topic,
                 group_id,
                 handler,
                 projection?,
                 parse_bootstrap_servers(config.kafka_bootstrap_servers),
                 handler_data
               )
             ) do
          {:ok, pid} ->
            {:cont, {:ok, Map.put(started, name, pid)}}

          {:error, reason} ->
            stop_subscribers(started)
            {:halt, {:error, {name, reason}}}
        end
      end)
    end
  end

  defp ensure_replay_generation(%{replay_generation_started: true} = state),
    do: {:ok, state}

  defp ensure_replay_generation(state) do
    case start_replay_generation(state.config) do
      :ok -> {:ok, %{state | replay_generation_started: true}}
      {:error, reason} -> {:error, reason}
    end
  end

  defp connect_subscribers(state) do
    case start_subscribers(state.config) do
      {:ok, subscribers} ->
        Logger.info("Kafka consumers connected for user owner commands and state projections.")

        monitored_subscribers = monitor_subscribers(subscribers)
        notify_projection_readiness(:connected)

        {:noreply,
         %{
           state
           | subscribers: monitored_subscribers,
             backoff_ms: @initial_backoff_ms,
             readiness_heartbeat_ref: schedule_readiness_heartbeat()
         }}

      {:error, reason} ->
        connection_failed(state, reason)
    end
  end

  defp connection_failed(state, reason) do
    notify_projection_readiness(:disconnected)
    Logger.warning("Kafka consumer connection failed: #{inspect(reason)}")
    schedule_reconnect(state.backoff_ms)
    {:noreply, %{state | backoff_ms: min(state.backoff_ms * 2, @max_backoff_ms)}}
  end

  defp start_replay_generation(config) do
    sources =
      Enum.map(consumer_specs(config), fn {_name, topic, group_id, _handler, projection?, _data} ->
        if projection?, do: {group_id, topic}, else: nil
      end)
      |> Enum.reject(&is_nil/1)

    ProjectionCheckpoint.start_replay_generation(sources)
  end

  defp ensure_client(config) do
    endpoints = parse_bootstrap_servers(config.kafka_bootstrap_servers)

    case :brod.start_client(endpoints, @client_id, []) do
      :ok -> :ok
      {:error, {:already_started, _pid}} -> :ok
      {:error, {:client_down, _client_id, _reason}} -> :ok
      other -> other
    end
  end

  defp subscriber_config(
         topic,
         group_id,
         handler,
         projection?,
         bootstrap_endpoints,
         handler_data
       ) do
    %{
      client: @client_id,
      group_id: group_id,
      topics: [topic],
      cb_module: handler,
      init_data:
        Map.merge(
          %{
            bootstrap_endpoints: bootstrap_endpoints,
            consumer_group: group_id,
            replay_owner: Ecto.UUID.generate()
          },
          handler_data
        ),
      message_type: :message,
      consumer_config: [
        begin_offset: :earliest,
        prefetch_count: 100,
        offset_reset_policy: :reset_to_earliest
      ],
      group_config: [
        offset_commit_policy: if(projection?, do: :consumer_managed, else: :commit_to_kafka_v2),
        rejoin_delay_seconds: 2
      ]
    }
  end

  defp consumer_specs(config) do
    [
      {:user_sync, config.kafka_topic, config.kafka_group_id, CoworkUser.Kafka.SyncHandler, false,
       %{}},
      {:identity_command, config.kafka_identity_command_topic,
       config.kafka_identity_command_group_id, CoworkUser.Kafka.IdentityCommandHandler, false,
       %{result_topic: config.kafka_identity_command_result_topic}},
      {:team_member, config.kafka_team_member_topic, config.kafka_team_member_group_id,
       CoworkUser.Kafka.TeamMemberHandler, true, %{}},
      {:presence, config.kafka_presence_topic, config.kafka_presence_group_id,
       CoworkUser.Kafka.PresenceHandler, true, %{}}
    ]
  end

  defp monitor_subscribers(subscribers) do
    Map.new(subscribers, fn {name, pid} -> {name, %{pid: pid, ref: Process.monitor(pid)}} end)
  end

  defp subscribers_healthy?(subscribers) when map_size(subscribers) > 0 do
    Enum.all?(subscribers, fn {_name, subscriber} -> Process.alive?(subscriber.pid) end)
  end

  defp subscribers_healthy?(_subscribers), do: false

  defp stop_subscribers(subscribers) do
    Enum.each(subscribers, fn
      {_name, %{pid: pid, ref: ref}} ->
        Process.demonitor(ref, [:flush])
        stop_subscriber(pid)

      {_name, pid} ->
        stop_subscriber(pid)
    end)
  end

  defp stop_subscriber(pid) when is_pid(pid) do
    if Process.alive?(pid), do: :brod_group_subscriber_v2.stop(pid)
  rescue
    _ -> :ok
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

  defp schedule_readiness_heartbeat do
    Process.send_after(self(), :announce_projection_readiness, @readiness_heartbeat_ms)
  end

  defp cancel_readiness_heartbeat(nil), do: :ok

  defp cancel_readiness_heartbeat(timer_ref) do
    Process.cancel_timer(timer_ref)
    :ok
  end

  defp notify_projection_readiness(:connected) do
    ProjectionReadiness.consumer_connected()
  catch
    :exit, _reason -> :ok
  end

  defp notify_projection_readiness(:disconnected) do
    ProjectionReadiness.consumer_disconnected()
  catch
    :exit, _reason -> :ok
  end
end
