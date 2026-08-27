defmodule CoworkUser.Kafka.ProjectionCheckpoint do
  alias CoworkUser.Repo

  @upsert_sql """
  INSERT INTO tb_kafka_projection_offsets (consumer_group, topic_name, partition_id, next_offset)
  VALUES (?, ?, ?, ?)
  ON DUPLICATE KEY UPDATE
      next_offset = GREATEST(next_offset, VALUES(next_offset)),
      updated_at = CURRENT_TIMESTAMP(6)
  """

  def initialize(consumer_group, topic, partition, beginning_offset) do
    advance(consumer_group, topic, partition, beginning_offset)
  end

  def advance(consumer_group, topic, partition, next_offset)
      when is_binary(consumer_group) and is_binary(topic) and is_integer(partition) and
             is_integer(next_offset) do
    case Ecto.Adapters.SQL.query(Repo, @upsert_sql, [
           consumer_group,
           topic,
           partition,
           next_offset
         ]) do
      {:ok, _result} -> :ok
      {:error, reason} -> {:error, reason}
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

  def offsets(consumer_groups_and_topics) when is_list(consumer_groups_and_topics) do
    Enum.reduce_while(consumer_groups_and_topics, {:ok, %{}}, fn {consumer_group, topic},
                                                                 {:ok, offsets} ->
      case Ecto.Adapters.SQL.query(
             Repo,
             """
             SELECT partition_id, next_offset
             FROM tb_kafka_projection_offsets
             WHERE consumer_group = ? AND topic_name = ?
             """,
             [consumer_group, topic]
           ) do
        {:ok, %{rows: rows}} ->
          topic_offsets =
            Map.new(rows, fn [partition, next_offset] ->
              {{consumer_group, topic, partition}, next_offset}
            end)

          {:cont, {:ok, Map.merge(offsets, topic_offsets)}}

        {:error, reason} ->
          {:halt, {:error, reason}}
      end
    end)
  end
end
