defmodule CoworkUser.Kafka.ProjectionQuarantine do
  alias CoworkUser.Repo

  @insert_sql """
  INSERT INTO tb_kafka_projection_quarantine
      (consumer_group, topic_name, partition_id, record_offset, record_key, payload, reason)
  VALUES (?, ?, ?, ?, ?, ?, ?)
  ON DUPLICATE KEY UPDATE
      record_key = VALUES(record_key),
      payload = VALUES(payload),
      reason = VALUES(reason),
      updated_at = CURRENT_TIMESTAMP(6)
  """

  def persist(entry) when is_map(entry) do
    case Ecto.Adapters.SQL.query(Repo, @insert_sql, [
           entry.consumer_group,
           entry.topic,
           entry.partition,
           entry.offset,
           entry.key,
           entry.payload,
           entry.reason
         ]) do
      {:ok, _result} -> :ok
      {:error, reason} -> {:error, reason}
    end
  end

  def entry(consumer_group, topic, partition, offset, record, reason) do
    %{
      consumer_group: consumer_group,
      topic: topic,
      partition: partition,
      offset: offset,
      key: normalize_binary(Map.get(record, :key)),
      payload: normalize_binary(Map.get(record, :payload)) || <<>>,
      reason: reason |> inspect(limit: 100, printable_limit: 4_000) |> String.slice(0, 1_000)
    }
  end

  defp normalize_binary(nil), do: nil
  defp normalize_binary(value) when is_binary(value), do: value
  defp normalize_binary(value), do: inspect(value, limit: 100, printable_limit: 4_000)
end
