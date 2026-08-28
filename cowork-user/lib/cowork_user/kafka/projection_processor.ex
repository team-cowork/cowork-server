defmodule CoworkUser.Kafka.ProjectionProcessor do
  alias CoworkUser.Kafka.{ProjectionBarrier, ProjectionCheckpoint, ProjectionQuarantine}
  alias CoworkUser.Kafka.ProjectionReadiness
  alias CoworkUser.Repo

  def process(replay_lease, offset, record, apply_event)
      when is_map(replay_lease) and is_integer(offset) and is_map(record) and
             is_function(apply_event, 0) do
    Repo.transaction(fn ->
      lease_or_rollback(replay_lease)
      result = apply_event.()

      case classify(result) do
        :applied ->
          checkpoint_or_rollback(replay_lease, offset + 1)
          :ok

        {:retry, reason} ->
          Repo.rollback(reason)

        {:quarantine, reason} ->
          quarantine_or_rollback(replay_lease, offset, record, reason)
          checkpoint_or_rollback(replay_lease, offset + 1)
          {:discard, reason}
      end
    end)
    |> case do
      {:ok, {:discard, _reason} = result} ->
        ProjectionReadiness.state_gap_detected()
        result

      {:ok, result} ->
        result

      {:error, reason} ->
        {:error, {:storage, reason}}
    end
  rescue
    exception -> {:error, {:storage, exception}}
  end

  def discard(replay_lease, offset, record, reason)
      when is_map(replay_lease) and is_integer(offset) and is_map(record) do
    process(replay_lease, offset, record, fn -> {:error, reason} end)
  end

  def process_barrier(replay_lease, offset, marker)
      when is_map(replay_lease) and is_integer(offset) do
    process(replay_lease, offset, %{key: nil, payload: <<>>}, fn ->
      case ProjectionBarrier.observe(replay_lease, offset, marker) do
        :ok ->
          case ProjectionCheckpoint.mark_snapshot_completed(
                 replay_lease,
                 offset,
                 marker.snapshot_id
               ) do
            :ok -> :ok
            {:error, reason} -> {:error, {:storage, reason}}
          end

        {:error, reason} ->
          {:error, {:storage, reason}}
      end
    end)
  end

  @doc false
  def classify(:ok), do: :applied
  def classify({:error, {:storage, reason}}), do: {:retry, reason}
  def classify({:error, reason}), do: {:quarantine, reason}
  def classify(other), do: {:quarantine, {:invalid_processor_result, other}}

  defp lease_or_rollback(replay_lease) do
    case ProjectionCheckpoint.lock_lease(replay_lease) do
      :ok -> :ok
      {:error, reason} -> Repo.rollback(reason)
    end
  end

  defp checkpoint_or_rollback(replay_lease, next_offset) do
    case ProjectionCheckpoint.advance(replay_lease, next_offset) do
      :ok -> :ok
      {:error, reason} -> Repo.rollback(reason)
    end
  end

  defp quarantine_or_rollback(replay_lease, offset, record, reason) do
    entry =
      ProjectionQuarantine.entry(
        replay_lease.consumer_group,
        replay_lease.topic,
        replay_lease.partition,
        offset,
        record,
        reason
      )

    case ProjectionQuarantine.persist(entry) do
      :ok ->
        case ProjectionCheckpoint.mark_invalid_record(replay_lease, offset) do
          :ok -> :ok
          {:error, storage_reason} -> Repo.rollback(storage_reason)
        end

      {:error, storage_reason} ->
        Repo.rollback(storage_reason)
    end
  end
end
