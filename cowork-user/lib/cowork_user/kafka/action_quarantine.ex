defmodule CoworkUser.Kafka.ActionQuarantine do
  alias CoworkUser.Repo

  @identity_error_code "INVALID_USER_IDENTITY_COMMAND"
  @identity_error_message "cowork-user rejected an invalid identity command"

  @upsert_sql """
  INSERT INTO tb_kafka_action_quarantine
      (consumer_group, topic_name, partition_id, record_offset, record_key, payload, reason)
  VALUES (?, ?, ?, ?, ?, ?, ?)
  ON DUPLICATE KEY UPDATE
      record_key = VALUES(record_key),
      payload = VALUES(payload),
      reason = VALUES(reason),
      updated_at = CURRENT_TIMESTAMP(6)
  """

  @lock_resolution_sql """
  SELECT terminal_result_payload
  FROM tb_kafka_action_quarantine
  WHERE consumer_group = ?
    AND topic_name = ?
    AND partition_id = ?
    AND record_offset = ?
  FOR UPDATE
  """

  @complete_resolution_sql """
  UPDATE tb_kafka_action_quarantine
  SET terminal_operation_id = ?,
      terminal_result_payload = ?,
      updated_at = CURRENT_TIMESTAMP(6)
  WHERE consumer_group = ?
    AND topic_name = ?
    AND partition_id = ?
    AND record_offset = ?
    AND terminal_result_payload IS NULL
  """

  def quarantine(entry) when is_map(entry) do
    Repo.transaction(fn ->
      persist!(entry)
      :ok
    end)
    |> transaction_result()
  rescue
    exception -> {:error, {:storage, exception}}
  end

  def quarantine_identity(entry, nil, _result_topic), do: quarantine(entry)

  def quarantine_identity(entry, operation_id, result_topic)
      when is_map(entry) and is_binary(operation_id) and is_binary(result_topic) do
    Repo.transaction(fn ->
      persist!(entry)

      case lock_resolution!(entry) do
        nil ->
          payload = operation_id |> identity_failure() |> Jason.encode!()

          {1, nil} =
            Repo.insert_all("tb_kafka_outbox", [
              %{topic: result_topic, event_key: operation_id, payload: payload}
            ])

          complete_resolution!(entry, operation_id, payload)

        _already_resolved ->
          :ok
      end

      :ok
    end)
    |> transaction_result()
  rescue
    exception -> {:error, {:storage, exception}}
  end

  def entry(consumer_group, topic, partition, offset, key, payload, reason) do
    %{
      consumer_group: consumer_group,
      topic: topic,
      partition: partition,
      offset: offset,
      key: normalize_binary(key),
      payload: normalize_binary(payload) || <<>>,
      reason: reason |> inspect(limit: 100, printable_limit: 4_000) |> String.slice(0, 1_000)
    }
  end

  def identity_failure(operation_id) do
    %{
      schemaVersion: 1,
      operationId: operation_id,
      status: "FAILED",
      error: %{code: @identity_error_code, message: @identity_error_message},
      occurredAt: DateTime.utc_now() |> DateTime.truncate(:microsecond) |> DateTime.to_iso8601()
    }
  end

  defp persist!(entry) do
    Ecto.Adapters.SQL.query!(Repo, @upsert_sql, [
      entry.consumer_group,
      entry.topic,
      entry.partition,
      entry.offset,
      entry.key,
      entry.payload,
      entry.reason
    ])

    :ok
  end

  defp lock_resolution!(entry) do
    case Ecto.Adapters.SQL.query!(Repo, @lock_resolution_sql, identity(entry)).rows do
      [[terminal_result_payload]] -> terminal_result_payload
      rows -> Repo.rollback({:invalid_action_quarantine_resolution, rows})
    end
  end

  defp complete_resolution!(entry, operation_id, payload) do
    result =
      Ecto.Adapters.SQL.query!(Repo, @complete_resolution_sql, [
        operation_id,
        payload | identity(entry)
      ])

    if result.num_rows == 1 do
      :ok
    else
      Repo.rollback(:identity_failure_resolution_conflict)
    end
  end

  defp identity(entry) do
    [entry.consumer_group, entry.topic, entry.partition, entry.offset]
  end

  defp transaction_result({:ok, :ok}), do: :ok
  defp transaction_result({:error, reason}), do: {:error, {:storage, reason}}

  defp normalize_binary(nil), do: nil
  defp normalize_binary(value) when is_binary(value), do: value
  defp normalize_binary(value), do: inspect(value, limit: 100, printable_limit: 4_000)
end
