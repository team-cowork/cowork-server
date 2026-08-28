defmodule CoworkUser.Kafka.ProjectionCheckpoint do
  alias CoworkUser.Repo

  @generation_upsert_sql """
  INSERT INTO tb_kafka_projection_generations
      (consumer_group, topic_name, replay_generation)
  VALUES (?, ?, 1)
  ON DUPLICATE KEY UPDATE
      replay_generation = replay_generation + 1,
      updated_at = CURRENT_TIMESTAMP(6)
  """

  @checkpoint_insert_sql """
  INSERT INTO tb_kafka_projection_offsets
      (consumer_group, topic_name, partition_id, next_offset,
       replay_generation, replay_token, replay_owner, replay_lease_expires_at)
  VALUES (?, ?, ?, ?, ?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 15 SECOND))
  """

  @checkpoint_reset_sql """
  UPDATE tb_kafka_projection_offsets
  SET next_offset = ?,
      snapshot_completed_offset = NULL,
      replay_generation = ?,
      replay_token = ?,
      replay_owner = ?,
      replay_lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 15 SECOND),
      updated_at = CURRENT_TIMESTAMP(6)
  WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
  """

  @doc """
  Starts one durable replay generation for all authoritative input topics.

  A generation is bumped before subscribers join their groups. Consequently,
  every old partition worker is fenced as soon as any replica starts a new
  consumer generation, and readiness cannot reuse offsets from the previous
  runtime generation.
  """
  def start_replay_generation(consumer_groups_and_topics)
      when is_list(consumer_groups_and_topics) do
    consumer_groups_and_topics
    |> Enum.uniq()
    |> then(fn sources ->
      Repo.transaction(fn ->
        Enum.each(sources, fn {consumer_group, topic} ->
          query_or_rollback(@generation_upsert_sql, [consumer_group, topic])
        end)

        :ok
      end)
    end)
    |> unwrap_transaction()
  rescue
    exception -> {:error, exception}
  end

  @doc """
  Claims an assigned partition and atomically resets its checkpoint and marker.

  The caller must seek the Kafka worker to `beginning_offset`. The returned
  lease fences a revoked worker from mutating the projection after a rebalance.
  """
  def begin_replay(
        consumer_group,
        topic,
        partition,
        beginning_offset,
        replay_owner,
        replay_token \\ Ecto.UUID.generate()
      )
      when is_binary(consumer_group) and is_binary(topic) and is_integer(partition) and
             is_integer(beginning_offset) and is_binary(replay_owner) and
             is_binary(replay_token) do
    Repo.transaction(fn ->
      generation = lock_generation_or_rollback(consumer_group, topic)
      checkpoint = lock_assignment_or_rollback(consumer_group, topic, partition)

      unless assignment_claimable?(checkpoint, generation, replay_owner) do
        Repo.rollback(:assignment_replay_lease_busy)
      end

      reset_assignment_or_rollback(
        checkpoint,
        consumer_group,
        topic,
        partition,
        beginning_offset,
        generation,
        replay_token,
        replay_owner
      )

      query_or_rollback(
        """
        DELETE FROM tb_kafka_projection_barriers
        WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
        """,
        [consumer_group, topic, partition]
      )

      %{
        consumer_group: consumer_group,
        topic: topic,
        partition: partition,
        generation: generation,
        token: replay_token,
        owner: replay_owner,
        beginning_offset: beginning_offset
      }
    end)
    |> unwrap_replay_transaction()
  rescue
    exception -> {:error, exception}
  end

  def assignment_lease(consumer_group, topic, partition, replay_owner) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           SELECT checkpoint.replay_generation, checkpoint.replay_token,
                  checkpoint.replay_owner, checkpoint.next_offset
           FROM tb_kafka_projection_offsets AS checkpoint
           JOIN tb_kafka_projection_generations AS generations
             ON generations.consumer_group = checkpoint.consumer_group
            AND generations.topic_name = checkpoint.topic_name
            AND generations.replay_generation = checkpoint.replay_generation
           WHERE checkpoint.consumer_group = ?
             AND checkpoint.topic_name = ?
             AND checkpoint.partition_id = ?
             AND checkpoint.replay_lease_expires_at > CURRENT_TIMESTAMP(6)
           """,
           [consumer_group, topic, partition]
         ) do
      {:ok, %{rows: [[generation, token, ^replay_owner, next_offset]]}}
      when is_integer(generation) and generation > 0 and is_binary(token) ->
        {:ok,
         %{
           consumer_group: consumer_group,
           topic: topic,
           partition: partition,
           generation: generation,
           token: token,
           owner: replay_owner,
           beginning_offset: next_offset
         }}

      {:ok, %{rows: [_row]}} ->
        {:error, :assignment_replay_superseded}

      {:ok, %{rows: []}} ->
        {:error, :assignment_replay_not_initialized}

      {:error, reason} ->
        {:error, reason}
    end
  end

  @doc """
  Locks and verifies the global generation and partition lease.

  This must run before the authoritative projection mutation in the same DB
  transaction. A revoked worker then rolls back instead of advancing a new
  assignment's checkpoint.
  """
  def lock_lease(%{
        consumer_group: consumer_group,
        topic: topic,
        partition: partition,
        generation: generation,
        token: token
      }) do
    if Repo.in_transaction?() do
      with {:ok, current_generation} <- lock_generation(consumer_group, topic),
           true <- current_generation == generation || {:error, :stale_replay_generation},
           {:ok, current_lease} <- lock_partition_lease(consumer_group, topic, partition),
           true <-
             lease_matches?(current_lease, generation, token) || {:error, :stale_replay_lease},
           :ok <- renew_locked_lease(consumer_group, topic, partition, generation, token) do
        :ok
      end
    else
      {:error, :replay_lease_lock_requires_transaction}
    end
  end

  def advance(lease, next_offset) when is_map(lease) and is_integer(next_offset) do
    if Repo.in_transaction?() do
      with :ok <- lock_lease(lease),
           {:ok, _result} <-
             Ecto.Adapters.SQL.query(
               Repo,
               """
               UPDATE tb_kafka_projection_offsets
               SET next_offset = GREATEST(next_offset, ?),
                   updated_at = CURRENT_TIMESTAMP(6)
               WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
               """,
               [
                 next_offset,
                 lease.consumer_group,
                 lease.topic,
                 lease.partition
               ]
             ) do
        :ok
      end
    else
      {:error, :checkpoint_advance_requires_transaction}
    end
  end

  def renew_assignment(lease) when is_map(lease) do
    Repo.transaction(fn ->
      case lock_lease(lease) do
        :ok -> :ok
        {:error, reason} -> Repo.rollback(reason)
      end
    end)
    |> unwrap_transaction()
  rescue
    exception -> {:error, exception}
  end

  def release_assignment(%{
        consumer_group: consumer_group,
        topic: topic,
        partition: partition,
        generation: generation,
        token: token
      }) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           UPDATE tb_kafka_projection_offsets
           SET replay_lease_expires_at = CURRENT_TIMESTAMP(6),
               updated_at = CURRENT_TIMESTAMP(6)
           WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
             AND replay_generation = ? AND BINARY replay_token = BINARY ?
           """,
           [consumer_group, topic, partition, generation, token]
         ) do
      {:ok, _result} -> :ok
      {:error, reason} -> {:error, reason}
    end
  rescue
    exception -> {:error, exception}
  end

  @doc """
  Durably records a quarantined state gap under the current replay lease.

  A newer invalid offset starts recovery over. Re-observing the same or an
  older invalid record during a full replay preserves any recovery progress
  already made after that gap.
  """
  def mark_invalid_record(lease, record_offset)
      when is_map(lease) and is_integer(record_offset) and record_offset >= 0 do
    update_recovery_state(lease, fn state -> latch_invalid_record(state, record_offset) end)
  end

  @doc """
  Records a valid full-snapshot marker and advances durable recovery state.

  The invalid-record latch clears only after two different, monotonically
  observed snapshot IDs after the bad offset. Producer-side snapshot locking
  makes those IDs serialized full snapshots rather than overlapping runs.
  """
  def mark_snapshot_completed(lease, marker_offset, snapshot_id)
      when is_map(lease) and is_integer(marker_offset) and marker_offset >= 0 and
             is_binary(snapshot_id) do
    update_recovery_state(lease, fn state ->
      observe_recovery_snapshot(state, marker_offset, snapshot_id)
    end)
  end

  @doc false
  def latch_invalid_record(state, record_offset)
      when is_map(state) and is_integer(record_offset) and record_offset >= 0 do
    case Map.get(state, :invalid_record_offset) do
      invalid_offset when is_integer(invalid_offset) and invalid_offset >= record_offset ->
        state

      _older_or_missing ->
        state
        |> Map.put(:invalid_record_offset, record_offset)
        |> Map.put(:recovery_snapshot_id, nil)
    end
  end

  @doc false
  def observe_recovery_snapshot(state, marker_offset, snapshot_id)
      when is_map(state) and is_integer(marker_offset) and marker_offset >= 0 and
             is_binary(snapshot_id) do
    previous_marker_offset = Map.get(state, :snapshot_completed_offset)

    if is_integer(previous_marker_offset) and marker_offset <= previous_marker_offset do
      state
    else
      previous_snapshot_id = Map.get(state, :last_snapshot_id)
      invalid_offset = Map.get(state, :invalid_record_offset)
      recovery_snapshot_id = Map.get(state, :recovery_snapshot_id)

      state =
        state
        |> Map.put(:snapshot_completed_offset, marker_offset)
        |> Map.put(:last_snapshot_id, snapshot_id)

      cond do
        is_nil(invalid_offset) ->
          Map.put(state, :recovery_snapshot_id, nil)

        marker_offset <= invalid_offset ->
          state

        snapshot_id == previous_snapshot_id or snapshot_id == recovery_snapshot_id ->
          state

        is_nil(recovery_snapshot_id) ->
          Map.put(state, :recovery_snapshot_id, snapshot_id)

        true ->
          state
          |> Map.put(:invalid_record_offset, nil)
          |> Map.put(:recovery_snapshot_id, nil)
      end
    end
  end

  def next_offset(consumer_group, topic, partition) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           SELECT next_offset
           FROM tb_kafka_projection_offsets
           WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
           """,
           [consumer_group, topic, partition]
         ) do
      {:ok, %{rows: [[next_offset]]}} -> {:ok, next_offset}
      {:ok, %{rows: []}} -> {:ok, nil}
      {:error, reason} -> {:error, reason}
    end
  end

  def states(consumer_groups_and_topics) when is_list(consumer_groups_and_topics) do
    load_partition_rows(consumer_groups_and_topics, fn
      [
        partition,
        next_offset,
        generation,
        token,
        invalid_record_offset,
        snapshot_completed_offset,
        last_snapshot_id,
        recovery_snapshot_id,
        replay_lease_active
      ] ->
        {partition,
         %{
           next_offset: next_offset,
           replay_generation: generation,
           replay_token: token,
           invalid_record_offset: invalid_record_offset,
           snapshot_completed_offset: snapshot_completed_offset,
           last_snapshot_id: last_snapshot_id,
           recovery_snapshot_id: recovery_snapshot_id,
           replay_lease_active: replay_lease_active == 1
         }}
    end)
  end

  def generations(consumer_groups_and_topics) when is_list(consumer_groups_and_topics) do
    Enum.reduce_while(Enum.uniq(consumer_groups_and_topics), {:ok, %{}}, fn
      {consumer_group, topic}, {:ok, generations} ->
        case Ecto.Adapters.SQL.query(
               Repo,
               """
               SELECT replay_generation
               FROM tb_kafka_projection_generations
               WHERE consumer_group = ? AND topic_name = ?
               """,
               [consumer_group, topic]
             ) do
          {:ok, %{rows: [[generation]]}} ->
            {:cont, {:ok, Map.put(generations, {consumer_group, topic}, generation)}}

          {:ok, %{rows: []}} ->
            {:cont, {:ok, generations}}

          {:error, reason} ->
            {:halt, {:error, reason}}
        end
    end)
  end

  @doc false
  def lease_matches?(
        %{replay_generation: generation, replay_token: token, replay_lease_active: true},
        generation,
        token
      )
      when is_integer(generation) and generation > 0 and is_binary(token),
      do: true

  def lease_matches?(_current, _generation, _token), do: false

  defp load_partition_rows(consumer_groups_and_topics, row_mapper) do
    Enum.reduce_while(Enum.uniq(consumer_groups_and_topics), {:ok, %{}}, fn
      {consumer_group, topic}, {:ok, states} ->
        case Ecto.Adapters.SQL.query(
               Repo,
               """
               SELECT partition_id, next_offset, replay_generation, replay_token,
                      invalid_record_offset, snapshot_completed_offset,
                      last_snapshot_id, recovery_snapshot_id,
                      replay_lease_expires_at > CURRENT_TIMESTAMP(6) AS replay_lease_active
               FROM tb_kafka_projection_offsets
               WHERE consumer_group = ? AND topic_name = ?
               """,
               [consumer_group, topic]
             ) do
          {:ok, %{rows: rows}} ->
            topic_states =
              Map.new(rows, fn row ->
                {partition, state} = row_mapper.(row)
                {{consumer_group, topic, partition}, state}
              end)

            {:cont, {:ok, Map.merge(states, topic_states)}}

          {:error, reason} ->
            {:halt, {:error, reason}}
        end
    end)
  end

  defp lock_generation(consumer_group, topic) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           SELECT replay_generation
           FROM tb_kafka_projection_generations
           WHERE consumer_group = ? AND topic_name = ?
           FOR UPDATE
           """,
           [consumer_group, topic]
         ) do
      {:ok, %{rows: [[generation]]}} -> {:ok, generation}
      {:ok, %{rows: []}} -> {:error, :replay_generation_not_started}
      {:error, reason} -> {:error, reason}
    end
  end

  defp lock_generation_or_rollback(consumer_group, topic) do
    case lock_generation(consumer_group, topic) do
      {:ok, generation} -> generation
      {:error, reason} -> Repo.rollback(reason)
    end
  end

  defp lock_partition_lease(consumer_group, topic, partition) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           SELECT replay_generation, replay_token,
                  replay_lease_expires_at > CURRENT_TIMESTAMP(6) AS replay_lease_active
           FROM tb_kafka_projection_offsets
           WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
           FOR UPDATE
           """,
           [consumer_group, topic, partition]
         ) do
      {:ok, %{rows: [[generation, token, lease_active]]}} ->
        {:ok,
         %{
           replay_generation: generation,
           replay_token: token,
           replay_lease_active: lease_active == 1
         }}

      {:ok, %{rows: []}} ->
        {:error, :assignment_replay_not_initialized}

      {:error, reason} ->
        {:error, reason}
    end
  end

  defp update_recovery_state(lease, transition) when is_function(transition, 1) do
    if Repo.in_transaction?() do
      with :ok <- lock_lease(lease),
           {:ok, state} <- load_recovery_state(lease),
           next_state <- transition.(state),
           {:ok, %{num_rows: updated_rows}} when updated_rows in [0, 1] <-
             persist_recovery_state(lease, next_state) do
        :ok
      else
        {:error, reason} -> {:error, reason}
      end
    else
      {:error, :checkpoint_recovery_update_requires_transaction}
    end
  end

  defp load_recovery_state(lease) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           SELECT invalid_record_offset, snapshot_completed_offset,
                  last_snapshot_id, recovery_snapshot_id
           FROM tb_kafka_projection_offsets
           WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
             AND replay_generation = ? AND BINARY replay_token = BINARY ?
           FOR UPDATE
           """,
           [
             lease.consumer_group,
             lease.topic,
             lease.partition,
             lease.generation,
             lease.token
           ]
         ) do
      {:ok,
       %{
         rows: [
           [
             invalid_record_offset,
             snapshot_completed_offset,
             last_snapshot_id,
             recovery_snapshot_id
           ]
         ]
       }} ->
        {:ok,
         %{
           invalid_record_offset: invalid_record_offset,
           snapshot_completed_offset: snapshot_completed_offset,
           last_snapshot_id: last_snapshot_id,
           recovery_snapshot_id: recovery_snapshot_id
         }}

      {:ok, %{rows: []}} ->
        {:error, :stale_replay_lease}

      {:error, reason} ->
        {:error, reason}
    end
  end

  defp persist_recovery_state(lease, state) do
    Ecto.Adapters.SQL.query(
      Repo,
      """
      UPDATE tb_kafka_projection_offsets
      SET invalid_record_offset = ?,
          snapshot_completed_offset = ?,
          last_snapshot_id = ?,
          recovery_snapshot_id = ?,
          updated_at = CURRENT_TIMESTAMP(6)
      WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
        AND replay_generation = ? AND BINARY replay_token = BINARY ?
      """,
      [
        state.invalid_record_offset,
        state.snapshot_completed_offset,
        state.last_snapshot_id,
        state.recovery_snapshot_id,
        lease.consumer_group,
        lease.topic,
        lease.partition,
        lease.generation,
        lease.token
      ]
    )
  end

  @doc false
  def assignment_claimable?(nil, _generation, _replay_owner), do: true

  def assignment_claimable?(checkpoint, generation, replay_owner) when is_map(checkpoint) do
    checkpoint.replay_generation < generation or
      checkpoint.replay_owner == replay_owner or
      checkpoint.replay_lease_active == false
  end

  defp lock_assignment_or_rollback(consumer_group, topic, partition) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           SELECT replay_generation, replay_owner,
                  replay_lease_expires_at > CURRENT_TIMESTAMP(6) AS replay_lease_active
           FROM tb_kafka_projection_offsets
           WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
           FOR UPDATE
           """,
           [consumer_group, topic, partition]
         ) do
      {:ok, %{rows: [[generation, owner, lease_active]]}} ->
        %{
          replay_generation: generation,
          replay_owner: owner,
          replay_lease_active: lease_active == 1
        }

      {:ok, %{rows: []}} ->
        nil

      {:error, reason} ->
        Repo.rollback(reason)
    end
  end

  defp reset_assignment_or_rollback(
         nil,
         consumer_group,
         topic,
         partition,
         beginning_offset,
         generation,
         replay_token,
         replay_owner
       ) do
    query_or_rollback(@checkpoint_insert_sql, [
      consumer_group,
      topic,
      partition,
      beginning_offset,
      generation,
      replay_token,
      replay_owner
    ])
  end

  defp reset_assignment_or_rollback(
         _checkpoint,
         consumer_group,
         topic,
         partition,
         beginning_offset,
         generation,
         replay_token,
         replay_owner
       ) do
    query_or_rollback(@checkpoint_reset_sql, [
      beginning_offset,
      generation,
      replay_token,
      replay_owner,
      consumer_group,
      topic,
      partition
    ])
  end

  defp renew_locked_lease(consumer_group, topic, partition, generation, token) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           UPDATE tb_kafka_projection_offsets
           SET replay_lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 15 SECOND),
               updated_at = CURRENT_TIMESTAMP(6)
           WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
             AND replay_generation = ? AND BINARY replay_token = BINARY ?
             AND replay_lease_expires_at > CURRENT_TIMESTAMP(6)
           """,
           [consumer_group, topic, partition, generation, token]
         ) do
      {:ok, %{num_rows: 1}} -> :ok
      {:ok, _result} -> {:error, :stale_replay_lease}
      {:error, reason} -> {:error, reason}
    end
  end

  defp query_or_rollback(sql, params) do
    case Ecto.Adapters.SQL.query(Repo, sql, params) do
      {:ok, _result} -> :ok
      {:error, reason} -> Repo.rollback(reason)
    end
  end

  defp unwrap_transaction({:ok, result}), do: result
  defp unwrap_transaction({:error, reason}), do: {:error, reason}

  defp unwrap_replay_transaction({:ok, replay_lease}), do: {:ok, replay_lease}
  defp unwrap_replay_transaction({:error, reason}), do: {:error, reason}
end
