defmodule CoworkUser.Kafka.ProjectionReadiness do
  use GenServer

  require Logger

  alias CoworkUser.Kafka.{ProjectionBarrier, ProjectionCheckpoint}

  @client_id :cowork_user_kafka_consumers
  @retry_ms 2_000
  @range_validation_interval_ms 2_000
  @readiness_refresh_interval_ms 500
  @readiness_cache :cowork_user_projection_readiness_cache

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  def ready?, do: cached_readiness(:ready)
  def team_ready?, do: cached_readiness(:team_ready)

  def consumer_connected do
    epoch = runtime_connection_epoch()
    set_runtime_consumer_connected(true)
    GenServer.cast(__MODULE__, {:consumer_connected, epoch})
  end

  def consumer_disconnected do
    epoch = advance_runtime_connection_epoch()
    set_runtime_consumer_connected(false)
    close_readiness_cache()
    GenServer.cast(__MODULE__, {:consumer_disconnected, epoch})
  end

  @doc false
  def runtime_connection_action(true, current_epoch, signal_epoch, :connected)
      when current_epoch == signal_epoch,
      do: :keep

  def runtime_connection_action(_connected?, _current_epoch, _signal_epoch, :connected),
    do: :recapture

  def runtime_connection_action(_connected?, _current_epoch, _signal_epoch, :disconnected),
    do: :close

  @doc false
  def replay_validation_action(:ok), do: :keep
  def replay_validation_action({:error, _reason}), do: :recapture
  def replay_validation_action({:invalid, _reason}), do: :halt

  def barrier_satisfied?(barriers, offsets) when map_size(barriers) > 0 do
    Enum.all?(barriers, fn {key, range} ->
      Map.get(offsets, key, -1) >= required_offset(range)
    end)
  end

  def barrier_satisfied?(_barriers, _offsets), do: false

  def checkpoint_replayable?(checkpoint, beginning_offset, end_offset)
      when is_integer(checkpoint) and is_integer(beginning_offset) and is_integer(end_offset) do
    checkpoint >= beginning_offset and checkpoint <= end_offset
  end

  @impl true
  def init(opts) do
    config = Keyword.fetch!(opts, :config)

    :ets.new(@readiness_cache, [
      :named_table,
      :public,
      :set,
      read_concurrency: true
    ])

    state = %{
      config: config,
      barriers: %{},
      capture_generation: 0,
      consumer_connected: not config.kafka_enabled,
      initialized: not config.kafka_enabled,
      invalid_latched: false,
      replay_range_valid: not config.kafka_enabled,
      runtime_connection_epoch: 0
    }

    :ets.insert(@readiness_cache, [
      {:consumer_connected, not config.kafka_enabled},
      {:runtime_connection_epoch, 0}
    ])

    cache_readiness(
      if(config.kafka_enabled, do: {false, false}, else: {true, true}),
      state.runtime_connection_epoch
    )

    schedule_range_validation()
    schedule_readiness_refresh()
    {:ok, state}
  end

  @impl true
  def handle_cast(
        {:consumer_connected, _epoch},
        %{config: %{kafka_enabled: false}} = state
      ) do
    {:noreply, state}
  end

  def handle_cast({:consumer_connected, epoch}, state)
      when epoch < state.runtime_connection_epoch do
    {:noreply, state}
  end

  def handle_cast({:consumer_connected, epoch}, %{invalid_latched: true} = state) do
    cache_readiness({false, false})
    {:noreply, %{state | consumer_connected: true, runtime_connection_epoch: epoch}}
  end

  def handle_cast({:consumer_connected, epoch}, state) do
    case runtime_connection_action(
           state.consumer_connected,
           state.runtime_connection_epoch,
           epoch,
           :connected
         ) do
      :recapture ->
        cache_readiness({false, false})
        generation = state.capture_generation + 1
        send(self(), {:initialize, generation})

        {:noreply,
         %{
           state
           | capture_generation: generation,
             consumer_connected: true,
             initialized: false,
             replay_range_valid: false,
             runtime_connection_epoch: epoch
         }}

      :keep ->
        {:noreply, state}
    end
  end

  def handle_cast(
        {:consumer_disconnected, _epoch},
        %{config: %{kafka_enabled: false}} = state
      ) do
    cache_readiness({true, true}, state.runtime_connection_epoch)
    {:noreply, state}
  end

  def handle_cast({:consumer_disconnected, epoch}, state)
      when epoch < state.runtime_connection_epoch do
    {:noreply, state}
  end

  def handle_cast({:consumer_disconnected, epoch}, state) do
    cache_readiness({false, false})

    {:noreply,
     %{
       state
       | capture_generation: state.capture_generation + 1,
         consumer_connected: false,
         initialized: false,
         replay_range_valid: false,
         runtime_connection_epoch: epoch
     }}
  end

  @impl true
  def handle_info({:initialize, _generation}, %{consumer_connected: false} = state) do
    cache_readiness({false, false})
    {:noreply, state}
  end

  def handle_info({:initialize, generation}, %{capture_generation: current} = state)
      when generation != current do
    {:noreply, state}
  end

  def handle_info({:initialize, generation}, state) do
    case build_barriers(state.config, Map.keys(state.barriers)) do
      {:ok, barriers} ->
        Logger.info("Kafka projection runtime barriers initialized.")
        next_state = %{state | barriers: barriers, initialized: true, replay_range_valid: true}

        cache_readiness(
          compute_readiness(next_state),
          next_state.runtime_connection_epoch
        )

        {:noreply, next_state}

      {:invalid, reason} ->
        Logger.error("Kafka projection replay state is invalid: #{inspect(reason)}")
        {:noreply, fail_closed_until_restart(state)}

      {:error, reason} ->
        Logger.warning("Kafka projection barrier initialization failed: #{inspect(reason)}")
        Process.send_after(self(), {:initialize, generation}, @retry_ms)
        {:noreply, state}
    end
  end

  def handle_info(:validate_replay_ranges, %{consumer_connected: false} = state) do
    cache_readiness({false, false})
    schedule_range_validation()
    {:noreply, state}
  end

  def handle_info(:validate_replay_ranges, %{initialized: false} = state) do
    cache_readiness({false, false})
    schedule_range_validation()
    {:noreply, state}
  end

  def handle_info(:validate_replay_ranges, state) do
    validation = validate_replay_ranges(state.config, Map.keys(state.barriers))

    next_state =
      case replay_validation_action(validation) do
        :keep ->
          state

        :halt ->
          {:invalid, reason} = validation
          Logger.error("Kafka projection replay range became invalid: #{inspect(reason)}")
          fail_closed_until_restart(state)

        :recapture ->
          {:error, reason} = validation
          Logger.warning("Kafka projection replay range validation failed: #{inspect(reason)}")
          fail_closed_and_recapture(state)
      end

    schedule_range_validation()
    {:noreply, next_state}
  end

  def handle_info(:refresh_readiness, state) do
    cache_readiness(compute_readiness(state), state.runtime_connection_epoch)
    schedule_readiness_refresh()
    {:noreply, state}
  end

  def handle_info(_message, state), do: {:noreply, state}

  defp build_barriers(config, expected_keys) do
    endpoints = parse_bootstrap_servers(config.kafka_bootstrap_servers)

    result =
      [
        {config.kafka_team_member_group_id, config.kafka_team_member_topic},
        {config.kafka_presence_group_id, config.kafka_presence_topic}
      ]
      |> Enum.reduce_while({:ok, %{}}, fn {consumer_group, topic}, {:ok, barriers} ->
        case topic_barriers(endpoints, consumer_group, topic) do
          {:ok, topic_barriers} -> {:cont, {:ok, Map.merge(barriers, topic_barriers)}}
          {:invalid, reason} -> {:halt, {:invalid, {topic, reason}}}
          {:error, reason} -> {:halt, {:error, {topic, reason}}}
        end
      end)

    case result do
      {:ok, barriers} -> validate_captured_topology(barriers, expected_keys)
      other -> other
    end
  end

  defp topic_barriers(endpoints, consumer_group, topic) do
    with {:ok, partition_count} when partition_count > 0 <-
           :brod.get_partitions_count(@client_id, topic),
         {:ok, marker_offsets} <- ProjectionBarrier.offsets([{consumer_group, topic}]) do
      0..(partition_count - 1)
      |> Enum.reduce_while({:ok, %{}}, fn partition, {:ok, barriers} ->
        with {:ok, beginning_offset} <-
               :brod.resolve_offset(endpoints, topic, partition, :earliest),
             {:ok, end_offset} <- :brod.resolve_offset(endpoints, topic, partition, :latest),
             {:ok, checkpoint} <-
               load_or_initialize_checkpoint(
                 consumer_group,
                 topic,
                 partition,
                 beginning_offset
               ) do
          marker_offset = Map.get(marker_offsets, {consumer_group, topic, partition})

          cond do
            not checkpoint_replayable?(checkpoint, beginning_offset, end_offset) ->
              {:halt,
               {:invalid,
                {:projection_checkpoint_outside_retained_log,
                 checkpoint: checkpoint,
                 beginning_offset: beginning_offset,
                 end_offset: end_offset}}}

            not marker_range_valid?(marker_offset, beginning_offset, end_offset) ->
              {:halt,
               {:invalid,
                {:projection_marker_outside_retained_log,
                 marker_offset: marker_offset,
                 beginning_offset: beginning_offset,
                 end_offset: end_offset}}}

            true ->
              range = %{beginning_offset: beginning_offset, end_offset: end_offset}
              {:cont, {:ok, Map.put(barriers, {consumer_group, topic, partition}, range)}}
          end
        else
          {:error, reason} -> {:halt, {:error, reason}}
        end
      end)
    else
      {:ok, partition_count} -> {:invalid, {:invalid_partition_count, partition_count}}
      {:error, reason} -> {:error, reason}
    end
  end

  defp validate_captured_topology(barriers, []), do: {:ok, barriers}

  defp validate_captured_topology(barriers, expected_keys) do
    current_keys = barriers |> Map.keys() |> Enum.sort()
    expected_keys = Enum.sort(expected_keys)

    if current_keys == expected_keys do
      {:ok, barriers}
    else
      {:invalid, {:partition_topology_changed, expected: expected_keys, current: current_keys}}
    end
  end

  defp load_or_initialize_checkpoint(consumer_group, topic, partition, beginning_offset) do
    case ProjectionCheckpoint.next_offset(consumer_group, topic, partition) do
      {:ok, nil} ->
        case ProjectionCheckpoint.initialize(
               consumer_group,
               topic,
               partition,
               beginning_offset
             ) do
          :ok -> {:ok, beginning_offset}
          {:error, reason} -> {:error, reason}
        end

      {:ok, checkpoint} ->
        {:ok, checkpoint}

      {:error, reason} ->
        {:error, reason}
    end
  end

  defp validate_replay_ranges(config, startup_keys) do
    endpoints = parse_bootstrap_servers(config.kafka_bootstrap_servers)

    [
      {config.kafka_team_member_group_id, config.kafka_team_member_topic},
      {config.kafka_presence_group_id, config.kafka_presence_topic}
    ]
    |> Enum.reduce_while(:ok, fn {consumer_group, topic}, :ok ->
      case validate_topic_range(endpoints, consumer_group, topic, startup_keys) do
        :ok -> {:cont, :ok}
        other -> {:halt, other}
      end
    end)
  end

  defp validate_topic_range(endpoints, consumer_group, topic, startup_keys) do
    with {:ok, partition_count} <- :brod.get_partitions_count(@client_id, topic),
         {:ok, marker_offsets} <- ProjectionBarrier.offsets([{consumer_group, topic}]) do
      current_partitions =
        if partition_count > 0, do: Enum.to_list(0..(partition_count - 1)), else: []

      expected_partitions =
        startup_keys
        |> Enum.filter(fn {group, key_topic, _partition} ->
          group == consumer_group and key_topic == topic
        end)
        |> Enum.map(fn {_group, _topic, partition} -> partition end)
        |> Enum.sort()

      if current_partitions != expected_partitions do
        {:invalid,
         {:partition_topology_changed,
          topic: topic, expected: expected_partitions, current: current_partitions}}
      else
        Enum.reduce_while(current_partitions, :ok, fn partition, :ok ->
          with {:ok, beginning_offset} <-
                 :brod.resolve_offset(endpoints, topic, partition, :earliest),
               {:ok, end_offset} <- :brod.resolve_offset(endpoints, topic, partition, :latest),
               {:ok, checkpoint} <-
                 ProjectionCheckpoint.next_offset(consumer_group, topic, partition) do
            marker_offset = Map.get(marker_offsets, {consumer_group, topic, partition})

            if is_integer(checkpoint) and
                 checkpoint_replayable?(checkpoint, beginning_offset, end_offset) and
                 marker_range_valid?(marker_offset, beginning_offset, end_offset) do
              {:cont, :ok}
            else
              {:halt,
               {:invalid,
                {:checkpoint_outside_retained_log,
                 topic: topic,
                 partition: partition,
                 checkpoint: checkpoint,
                 marker_offset: marker_offset,
                 beginning_offset: beginning_offset,
                 end_offset: end_offset}}}
            end
          else
            {:error, reason} -> {:halt, {:error, reason}}
          end
        end)
      end
    else
      {:error, reason} -> {:error, reason}
    end
  end

  defp compute_readiness(%{config: %{kafka_enabled: false}}), do: {true, true}
  defp compute_readiness(%{consumer_connected: false}), do: {false, false}
  defp compute_readiness(%{initialized: false}), do: {false, false}
  defp compute_readiness(%{invalid_latched: true}), do: {false, false}
  defp compute_readiness(%{replay_range_valid: false}), do: {false, false}

  defp compute_readiness(state) do
    keys = Map.keys(state.barriers)

    team_keys =
      Enum.filter(keys, fn {_group, topic, _partition} ->
        topic == state.config.kafka_team_member_topic
      end)

    groups_and_topics =
      keys |> Enum.map(fn {group, topic, _partition} -> {group, topic} end) |> Enum.uniq()

    with {:ok, offsets} <- ProjectionCheckpoint.offsets(groups_and_topics),
         {:ok, marker_offsets} <- ProjectionBarrier.offsets(groups_and_topics) do
      {
        keys_ready?(state.barriers, keys, offsets, marker_offsets),
        keys_ready?(state.barriers, team_keys, offsets, marker_offsets)
      }
    else
      {:error, _reason} -> {false, false}
    end
  rescue
    _exception -> {false, false}
  end

  defp keys_ready?(_barriers, [], _offsets, _marker_offsets), do: false

  defp keys_ready?(barriers, keys, offsets, marker_offsets) do
    barrier = Map.take(barriers, keys)

    barrier_satisfied?(barrier, offsets) and
      ProjectionBarrier.markers_satisfied?(barrier, offsets, marker_offsets)
  end

  defp cached_readiness(key) do
    case :ets.lookup(@readiness_cache, key) do
      [{^key, value}] -> value
      [] -> false
    end
  rescue
    ArgumentError -> false
  end

  defp cache_readiness({ready, team_ready}, expected_runtime_epoch \\ nil) do
    runtime_valid =
      is_nil(expected_runtime_epoch) or
        runtime_connection_matches?(expected_runtime_epoch)

    :ets.insert(@readiness_cache, [
      {:ready, ready and runtime_valid},
      {:team_ready, team_ready and runtime_valid}
    ])

    :ok
  end

  defp close_readiness_cache do
    cache_readiness({false, false})
  rescue
    ArgumentError -> :ok
  end

  defp set_runtime_consumer_connected(connected?) do
    :ets.insert(@readiness_cache, {:consumer_connected, connected?})
    :ok
  rescue
    ArgumentError -> :ok
  end

  defp advance_runtime_connection_epoch do
    :ets.update_counter(@readiness_cache, :runtime_connection_epoch, {2, 1}, {
      :runtime_connection_epoch,
      0
    })
  rescue
    ArgumentError -> 0
  end

  defp runtime_connection_epoch do
    case :ets.lookup(@readiness_cache, :runtime_connection_epoch) do
      [{:runtime_connection_epoch, epoch}] -> epoch
      [] -> 0
    end
  rescue
    ArgumentError -> 0
  end

  defp runtime_connection_matches?(expected_epoch) do
    case :ets.lookup(@readiness_cache, :consumer_connected) do
      [{:consumer_connected, true}] -> runtime_connection_epoch() == expected_epoch
      _other -> false
    end
  rescue
    ArgumentError -> false
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

  defp schedule_range_validation do
    Process.send_after(self(), :validate_replay_ranges, @range_validation_interval_ms)
  end

  defp schedule_readiness_refresh do
    Process.send_after(self(), :refresh_readiness, @readiness_refresh_interval_ms)
  end

  defp fail_closed_and_recapture(state) do
    cache_readiness({false, false})
    generation = state.capture_generation + 1
    Process.send_after(self(), {:initialize, generation}, @retry_ms)

    %{
      state
      | capture_generation: generation,
        initialized: false,
        replay_range_valid: false
    }
  end

  defp fail_closed_until_restart(state) do
    cache_readiness({false, false})
    %{state | initialized: false, invalid_latched: true, replay_range_valid: false}
  end

  defp required_offset(%{end_offset: end_offset}), do: end_offset
  defp required_offset(end_offset) when is_integer(end_offset), do: end_offset

  defp marker_range_valid?(nil, _beginning_offset, _end_offset), do: true

  defp marker_range_valid?(marker_offset, beginning_offset, end_offset) do
    ProjectionBarrier.marker_replayable?(marker_offset, beginning_offset, end_offset)
  end
end
