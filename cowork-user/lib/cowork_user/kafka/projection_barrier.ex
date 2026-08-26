defmodule CoworkUser.Kafka.ProjectionBarrier do
  alias CoworkUser.Repo

  @event_type "PROJECTION_SNAPSHOT_COMPLETED"
  @key_prefix "__cowork_projection_snapshot_complete__"

  def event_type, do: @event_type

  def key(partition) when is_integer(partition) and partition >= 0,
    do: "#{@key_prefix}:#{partition}"

  def event(topic, partition, snapshot_id, occurred_at, source)
      when is_binary(topic) and is_integer(partition) and partition >= 0 and
             is_binary(snapshot_id) and is_binary(source) do
    %{
      eventType: @event_type,
      topic: topic,
      partition: partition,
      snapshotId: snapshot_id,
      occurredAt: DateTime.to_iso8601(occurred_at),
      source: source
    }
  end

  def parse(payload, record_key, expected_topic, expected_partition, expected_source)
      when is_map(payload) do
    if Map.get(payload, "eventType") == @event_type do
      parse_marker(payload, record_key, expected_topic, expected_partition, expected_source)
    else
      :not_barrier
    end
  end

  def parse(_payload, _record_key, _expected_topic, _expected_partition, _expected_source),
    do: :not_barrier

  def observe(consumer_group, topic, partition, marker_offset, marker) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           INSERT INTO tb_kafka_projection_barriers
               (consumer_group, topic_name, partition_id, marker_offset, snapshot_id, source_service, occurred_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)
           ON DUPLICATE KEY UPDATE
               snapshot_id = IF(VALUES(marker_offset) >= marker_offset, VALUES(snapshot_id), snapshot_id),
               source_service = IF(
                   VALUES(marker_offset) >= marker_offset,
                   VALUES(source_service),
                   source_service
               ),
               occurred_at = IF(VALUES(marker_offset) >= marker_offset, VALUES(occurred_at), occurred_at),
               marker_offset = GREATEST(marker_offset, VALUES(marker_offset))
           """,
           [
             consumer_group,
             topic,
             partition,
             marker_offset,
             marker.snapshot_id,
             marker.source,
             DateTime.to_naive(marker.occurred_at)
           ]
         ) do
      {:ok, _result} -> :ok
      {:error, reason} -> {:error, reason}
    end
  end

  def offsets(consumer_groups_and_topics) when is_list(consumer_groups_and_topics) do
    Enum.reduce_while(consumer_groups_and_topics, {:ok, %{}}, fn {consumer_group, topic},
                                                                 {:ok, offsets} ->
      case Ecto.Adapters.SQL.query(
             Repo,
             """
             SELECT partition_id, marker_offset
             FROM tb_kafka_projection_barriers
             WHERE consumer_group = ? AND topic_name = ?
             """,
             [consumer_group, topic]
           ) do
        {:ok, %{rows: rows}} ->
          topic_offsets =
            Map.new(rows, fn [partition, marker_offset] ->
              {{consumer_group, topic, partition}, marker_offset}
            end)

          {:cont, {:ok, Map.merge(offsets, topic_offsets)}}

        {:error, reason} ->
          {:halt, {:error, reason}}
      end
    end)
  end

  def markers_satisfied?(barriers, checkpoints, marker_offsets) when map_size(barriers) > 0 do
    Enum.all?(barriers, fn {key, range} ->
      marker_offset = Map.get(marker_offsets, key)
      checkpoint = Map.get(checkpoints, key)

      is_integer(marker_offset) and
        is_integer(checkpoint) and
        marker_offset >= range.beginning_offset and
        marker_offset < checkpoint
    end)
  end

  def markers_satisfied?(_barriers, _checkpoints, _marker_offsets), do: false

  def marker_replayable?(marker_offset, beginning_offset, end_offset)
      when is_integer(marker_offset) and is_integer(beginning_offset) and is_integer(end_offset) do
    marker_offset >= beginning_offset and marker_offset < end_offset
  end

  def marker_replayable?(_marker_offset, _beginning_offset, _end_offset), do: false

  defp parse_marker(payload, record_key, expected_topic, expected_partition, expected_source) do
    with true <- record_key == key(expected_partition) || {:error, :invalid_barrier_key},
         true <- Map.get(payload, "topic") == expected_topic || {:error, :invalid_barrier_topic},
         true <-
           Map.get(payload, "partition") == expected_partition ||
             {:error, :invalid_barrier_partition},
         snapshot_id when is_binary(snapshot_id) <- Map.get(payload, "snapshotId"),
         {:ok, _uuid} <- Ecto.UUID.cast(snapshot_id),
         source when is_binary(source) and source != "" <- Map.get(payload, "source"),
         true <- source == expected_source || {:error, :invalid_barrier_source},
         occurred_at when is_binary(occurred_at) <- Map.get(payload, "occurredAt"),
         {:ok, parsed_at, _offset} <- DateTime.from_iso8601(occurred_at) do
      {:ok, %{snapshot_id: snapshot_id, source: source, occurred_at: parsed_at}}
    else
      {:error, reason} -> {:error, reason}
      _other -> {:error, :invalid_projection_snapshot_barrier}
    end
  end
end
