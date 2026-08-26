defmodule CoworkUser.Kafka.ProjectionProcessor do
  alias CoworkUser.Kafka.{ProjectionBarrier, ProjectionCheckpoint, ProjectionQuarantine}
  alias CoworkUser.Repo

  def process(consumer_group, topic, partition, offset, record, apply_event)
      when is_integer(offset) and is_map(record) and is_function(apply_event, 0) do
    Repo.transaction(fn ->
      result = apply_event.()

      case classify(result) do
        :applied ->
          checkpoint_or_rollback(consumer_group, topic, partition, offset + 1)
          :ok

        {:retry, reason} ->
          Repo.rollback(reason)

        {:quarantine, reason} ->
          quarantine_or_rollback(consumer_group, topic, partition, offset, record, reason)
          checkpoint_or_rollback(consumer_group, topic, partition, offset + 1)
          {:discard, reason}
      end
    end)
    |> case do
      {:ok, result} -> result
      {:error, reason} -> {:error, {:storage, reason}}
    end
  rescue
    exception -> {:error, {:storage, exception}}
  end

  def discard(consumer_group, topic, partition, offset, record, reason)
      when is_integer(offset) and is_map(record) do
    process(consumer_group, topic, partition, offset, record, fn -> {:error, reason} end)
  end

  def process_barrier(consumer_group, topic, partition, offset, marker)
      when is_integer(offset) do
    process(consumer_group, topic, partition, offset, %{key: nil, payload: <<>>}, fn ->
      case ProjectionBarrier.observe(consumer_group, topic, partition, offset, marker) do
        :ok -> :ok
        {:error, reason} -> {:error, {:storage, reason}}
      end
    end)
  end

  @doc false
  def classify(:ok), do: :applied
  def classify({:error, {:storage, reason}}), do: {:retry, reason}
  def classify({:error, reason}), do: {:quarantine, reason}
  def classify(other), do: {:quarantine, {:invalid_processor_result, other}}

  defp checkpoint_or_rollback(consumer_group, topic, partition, next_offset) do
    case ProjectionCheckpoint.advance(consumer_group, topic, partition, next_offset) do
      :ok -> :ok
      {:error, reason} -> Repo.rollback(reason)
    end
  end

  defp quarantine_or_rollback(consumer_group, topic, partition, offset, record, reason) do
    entry =
      ProjectionQuarantine.entry(
        consumer_group,
        topic,
        partition,
        offset,
        record,
        reason
      )

    case ProjectionQuarantine.persist(entry) do
      :ok -> :ok
      {:error, storage_reason} -> Repo.rollback(storage_reason)
    end
  end
end
