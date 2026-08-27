defmodule CoworkUser.Kafka.ProjectionReadiness do
  use GenServer

  require Logger

  alias CoworkUser.Kafka.{ProjectionBarrier, ProjectionCheckpoint}

  @client_id :cowork_user_kafka_consumers
  @retry_ms 2_000
  @range_validation_interval_ms 2_000
  @readiness_refresh_interval_ms 500
  @readiness_cache :cowork_user_projection_readiness_cache
  @marker_fetch_options %{max_wait_time: 100, min_bytes: 1, max_bytes: 1_048_576}

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  def ready?, do: cached_readiness(:ready)
  def team_ready?, do: cached_readiness(:team_ready)

  def current? do
    GenServer.call(__MODULE__, :current?, 10_000)
  catch
    :exit, _reason -> false
  end

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

  def assignment_replay_started(consumer_group, topic, partition) do
    close_readiness_cache()

    GenServer.call(
      __MODULE__,
      {:assignment_replay_started, {consumer_group, topic, partition}},
      10_000
    )
  catch
    :exit, reason -> {:error, reason}
  end

  def assignment_replay_finished(consumer_group, topic, partition) do
    GenServer.call(
      __MODULE__,
      {:assignment_replay_finished, {consumer_group, topic, partition}},
      10_000
    )
  catch
    :exit, reason -> {:error, reason}
  end

  def state_gap_detected do
    close_readiness_cache()
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

  @doc false
  def profile_snapshot_transition_action(false, true), do: :publish_now
  def profile_snapshot_transition_action(_previous_ready?, _next_ready?), do: :keep

  def barrier_satisfied?(barriers, offsets) when map_size(barriers) > 0 do
    Enum.all?(barriers, fn {key, range} ->
      Map.get(offsets, key, -1) == required_offset(range)
    end)
  end

  def barrier_satisfied?(_barriers, _offsets), do: false

  def checkpoint_replayable?(checkpoint, beginning_offset, end_offset)
      when is_integer(checkpoint) and is_integer(beginning_offset) and is_integer(end_offset) do
    checkpoint >= beginning_offset and checkpoint <= end_offset
  end

  @doc false
  def partition_ready?(
        %{
          beginning_offset: beginning_offset,
          end_offset: end_offset,
          replay_generation: generation,
          validated_marker: validated_marker
        },
        %{
          next_offset: end_offset,
          replay_generation: generation,
          replay_token: token,
          replay_lease_active: true,
          invalid_record_offset: nil,
          snapshot_completed_offset: marker_offset,
          last_snapshot_id: snapshot_id
        },
        %{
          marker_offset: marker_offset,
          replay_generation: generation,
          replay_token: token,
          snapshot_id: snapshot_id
        }
      )
      when is_binary(token) and is_map(validated_marker) do
    marker_offset >= beginning_offset and marker_offset < end_offset and
      validated_marker.marker_offset == marker_offset and
      validated_marker.snapshot_id == snapshot_id and
      validated_marker.replay_generation == generation and
      validated_marker.replay_token == token
  end

  def partition_ready?(_range, _checkpoint, _marker), do: false

  @doc false
  def partition_count_contract(_topic, _reserved, partition_count)
      when is_integer(partition_count) and partition_count > 0,
      do: :ok

  def partition_count_contract(_topic, _reserved, partition_count),
    do: {:invalid, {:invalid_partition_count, partition_count}}

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
      assignments_replaying: %{},
      capture_generation: 0,
      consumer_connected: false,
      initialized: false,
      invalid_latched: false,
      replay_range_valid: false,
      runtime_connection_epoch: 0
    }

    :ets.insert(@readiness_cache, [
      {:consumer_connected, false},
      {:runtime_connection_epoch, 0}
    ])

    cache_readiness({false, false}, state.runtime_connection_epoch)

    schedule_range_validation()
    schedule_readiness_refresh()
    {:ok, state}
  end

  @impl true
  def handle_cast(
        {:consumer_connected, _epoch},
        %{config: %{kafka_enabled: false}} = state
      ) do
    cache_readiness({false, false}, state.runtime_connection_epoch)
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
    cache_readiness({false, false}, state.runtime_connection_epoch)
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
       | assignments_replaying: %{},
         capture_generation: state.capture_generation + 1,
         consumer_connected: false,
         initialized: false,
         replay_range_valid: false,
         runtime_connection_epoch: epoch
     }}
  end

  @impl true
  def handle_call({:assignment_replay_started, key}, _from, state) do
    close_readiness_cache()

    {:reply, :ok,
     %{state | assignments_replaying: Map.put(state.assignments_replaying, key, true)}}
  end

  def handle_call({:assignment_replay_finished, key}, _from, state) do
    assignments_replaying = Map.delete(state.assignments_replaying, key)

    if map_size(assignments_replaying) == 0 do
      generation = state.capture_generation + 1
      send(self(), {:initialize, generation})

      {:reply, :ok,
       %{
         state
         | assignments_replaying: assignments_replaying,
           capture_generation: generation,
           initialized: false,
           replay_range_valid: false
       }}
    else
      {:reply, :ok, %{state | assignments_replaying: assignments_replaying}}
    end
  end

  def handle_call(:current?, _from, %{consumer_connected: false} = state) do
    cache_readiness({false, false})
    {:reply, false, state}
  end

  def handle_call(:current?, _from, %{initialized: false} = state) do
    cache_readiness({false, false})
    {:reply, false, state}
  end

  def handle_call(:current?, _from, state) do
    {ready?, next_state} = refresh_current_barriers(state)
    {:reply, ready?, next_state}
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

      {:replay, reason} ->
        Logger.warning("Kafka projection source identity changed: #{inspect(reason)}")
        {:noreply, fail_closed_for_replay(state, reason)}

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
    {_ready?, next_state} = refresh_current_barriers(state)

    schedule_range_validation()
    {:noreply, next_state}
  end

  def handle_info(
        :refresh_readiness,
        %{consumer_connected: true, initialized: true} = state
      ) do
    {_ready?, next_state} = refresh_current_barriers(state)
    schedule_readiness_refresh()
    {:noreply, next_state}
  end

  def handle_info(:refresh_readiness, state) do
    cache_readiness({false, false}, state.runtime_connection_epoch)
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
        case topic_barriers(
               endpoints,
               consumer_group,
               topic,
               nil
             ) do
          {:ok, topic_barriers} -> {:cont, {:ok, Map.merge(barriers, topic_barriers)}}
          {:invalid, reason} -> {:halt, {:invalid, {topic, reason}}}
          {:replay, reason} -> {:halt, {:replay, {topic, reason}}}
          {:error, reason} -> {:halt, {:error, {topic, reason}}}
        end
      end)

    case result do
      {:ok, barriers} -> validate_captured_topology(barriers, expected_keys)
      other -> other
    end
  end

  defp topic_barriers(endpoints, consumer_group, topic, identity_topic) do
    with {:ok, partition_count} <-
           :brod.get_partitions_count(@client_id, topic),
         :ok <- partition_count_contract(topic, identity_topic, partition_count),
         {:ok, checkpoints} <- ProjectionCheckpoint.states([{consumer_group, topic}]),
         {:ok, markers} <- ProjectionBarrier.states([{consumer_group, topic}]),
         {:ok, generations} <- ProjectionCheckpoint.generations([{consumer_group, topic}]) do
      generation = Map.get(generations, {consumer_group, topic})

      if is_integer(generation) and generation > 0 do
        0..(partition_count - 1)
        |> Enum.reduce_while({:ok, %{}}, fn partition, {:ok, barriers} ->
          key = {consumer_group, topic, partition}

          with {:ok, beginning_offset} <-
                 :brod.resolve_offset(endpoints, topic, partition, :earliest),
               {:ok, end_offset} <-
                 :brod.resolve_offset(endpoints, topic, partition, :latest) do
            checkpoint = Map.get(checkpoints, key)
            marker = Map.get(markers, key)

            case validate_partition_replay(
                   endpoints,
                   topic,
                   partition,
                   generation,
                   beginning_offset,
                   end_offset,
                   checkpoint,
                   marker
                 ) do
              {:ok, validated_marker} ->
                range = %{
                  beginning_offset: beginning_offset,
                  end_offset: end_offset,
                  replay_generation: generation,
                  validated_marker: validated_marker
                }

                {:cont, {:ok, Map.put(barriers, key, range)}}

              {:replay, reason} ->
                {:halt, {:replay, {partition, reason}}}

              {:error, reason} ->
                {:halt, {:error, {partition, reason}}}
            end
          else
            {:error, reason} -> {:halt, {:error, {partition, reason}}}
          end
        end)
      else
        {:error, :replay_generation_not_started}
      end
    else
      {:invalid, reason} -> {:invalid, reason}
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

  defp compute_readiness(%{config: %{kafka_enabled: false}}), do: {false, false}
  defp compute_readiness(%{consumer_connected: false}), do: {false, false}

  defp compute_readiness(%{assignments_replaying: assignments}) when map_size(assignments) > 0,
    do: {false, false}

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

    with {:ok, checkpoints} <- ProjectionCheckpoint.states(groups_and_topics),
         {:ok, markers} <- ProjectionBarrier.states(groups_and_topics),
         {:ok, generations} <- ProjectionCheckpoint.generations(groups_and_topics) do
      {
        keys_ready?(state.barriers, keys, checkpoints, markers, generations),
        keys_ready?(state.barriers, team_keys, checkpoints, markers, generations)
      }
    else
      {:error, _reason} -> {false, false}
    end
  rescue
    exception ->
      Logger.warning(
        "Kafka projection readiness check failed: #{Exception.message(exception)}"
      )

      {false, false}
  end

  defp refresh_current_barriers(state) do
    case build_barriers(state.config, Map.keys(state.barriers)) do
      {:ok, current_barriers} ->
        current_state = %{state | barriers: current_barriers}
        {ready?, team_ready?} = compute_readiness(current_state)

        cache_readiness(
          {ready?, team_ready?},
          current_state.runtime_connection_epoch
        )

        {ready?, current_state}

      {:invalid, reason} ->
        Logger.error("Kafka projection replay range became invalid: #{inspect(reason)}")
        {false, fail_closed_until_restart(state)}

      {:replay, reason} ->
        Logger.warning("Kafka projection source identity changed: #{inspect(reason)}")
        {false, fail_closed_for_replay(state, reason)}

      {:error, reason} ->
        Logger.warning("Kafka projection replay range validation failed: #{inspect(reason)}")
        {false, fail_closed_and_recapture(state)}
    end
  end

  defp keys_ready?(_barriers, [], _checkpoints, _markers, _generations), do: false

  defp keys_ready?(barriers, keys, checkpoints, markers, generations) do
    Enum.all?(keys, fn key ->
      {consumer_group, topic, _partition} = key
      range = Map.get(barriers, key)

      is_map(range) and
        Map.get(generations, {consumer_group, topic}) == range.replay_generation and
        partition_ready?(range, Map.get(checkpoints, key), Map.get(markers, key))
    end)
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

    previous_ready = cached_readiness(:ready)
    next_ready = ready and runtime_valid

    :ets.insert(@readiness_cache, [
      {:ready, next_ready},
      {:team_ready, team_ready and runtime_valid}
    ])

    if profile_snapshot_transition_action(previous_ready, next_ready) == :publish_now do
      CoworkUser.Kafka.ProfileSnapshotPublisher.publish_now()
    end

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

  defp validate_partition_replay(
         _endpoints,
         _topic,
         _partition,
         _generation,
         _beginning_offset,
         _end_offset,
         nil,
         _marker
       ),
       do: {:ok, nil}

  defp validate_partition_replay(
         endpoints,
         topic,
         partition,
         generation,
         beginning_offset,
         end_offset,
         checkpoint,
         marker
       ) do
    cond do
      checkpoint.replay_generation != generation ->
        {:ok, nil}

      not is_binary(checkpoint.replay_token) ->
        {:replay, :missing_replay_token}

      not checkpoint_replayable?(
        checkpoint.next_offset,
        beginning_offset,
        end_offset
      ) ->
        {:replay,
         {:projection_checkpoint_outside_retained_log,
          checkpoint: checkpoint.next_offset,
          beginning_offset: beginning_offset,
          end_offset: end_offset}}

      is_nil(marker) ->
        {:ok, nil}

      marker.replay_generation != generation or
          marker.replay_token != checkpoint.replay_token ->
        {:ok, nil}

      not marker_range_valid?(marker.marker_offset, beginning_offset, end_offset) ->
        {:replay,
         {:projection_marker_outside_retained_log,
          marker_offset: marker.marker_offset,
          beginning_offset: beginning_offset,
          end_offset: end_offset}}

      true ->
        validate_broker_marker(endpoints, topic, partition, marker)
    end
  end

  defp validate_broker_marker(endpoints, topic, partition, marker) do
    case :brod.fetch(
           endpoints,
           topic,
           partition,
           marker.marker_offset,
           @marker_fetch_options
         ) do
      {:ok, {_high_watermark, messages}} ->
        record =
          Enum.find(messages, fn
            {:kafka_message, offset, _key, _value, _ts_type, _ts, _headers} ->
              offset == marker.marker_offset

            _other ->
              false
          end)

        if marker_identity_matches?(record, topic, partition, marker) do
          {:ok,
           %{
             marker_offset: marker.marker_offset,
             replay_generation: marker.replay_generation,
             replay_token: marker.replay_token,
             snapshot_id: marker.snapshot_id
           }}
        else
          {:replay,
           {:projection_marker_identity_mismatch,
            marker_offset: marker.marker_offset, snapshot_id: marker.snapshot_id}}
        end

      {:error, :offset_out_of_range} ->
        {:replay, {:projection_marker_offset_missing, marker.marker_offset}}

      {:error, reason} ->
        {:error, {:projection_marker_fetch_failed, reason}}
    end
  end

  @doc false
  def marker_identity_matches?(
        {:kafka_message, offset, key, value, _ts_type, _ts, _headers},
        topic,
        partition,
        marker
      )
      when is_binary(value) do
    with true <- offset == marker.marker_offset,
         {:ok, %{} = payload} <- Jason.decode(value),
         {:ok, parsed_marker} <-
           ProjectionBarrier.parse(
             payload,
             to_string(key),
             topic,
             partition,
             marker.source
           ) do
      parsed_marker.snapshot_id == marker.snapshot_id and
        parsed_marker.source == marker.source
    else
      _other -> false
    end
  end

  def marker_identity_matches?(_record, _topic, _partition, _marker), do: false

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

  defp fail_closed_for_replay(state, reason) do
    cache_readiness({false, false})
    request_full_replay(reason)

    %{
      state
      | capture_generation: state.capture_generation + 1,
        initialized: false,
        invalid_latched: false,
        replay_range_valid: false
    }
  end

  defp fail_closed_until_restart(state) do
    cache_readiness({false, false})
    %{state | initialized: false, invalid_latched: true, replay_range_valid: false}
  end

  defp request_full_replay(reason) do
    CoworkUser.Kafka.Consumer.force_replay(reason)
  catch
    :exit, _reason -> :ok
  end

  defp required_offset(%{end_offset: end_offset}), do: end_offset
  defp required_offset(end_offset) when is_integer(end_offset), do: end_offset

  defp marker_range_valid?(nil, _beginning_offset, _end_offset), do: true

  defp marker_range_valid?(marker_offset, beginning_offset, end_offset) do
    ProjectionBarrier.marker_replayable?(marker_offset, beginning_offset, end_offset)
  end
end
