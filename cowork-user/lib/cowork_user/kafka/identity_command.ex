defmodule CoworkUser.Kafka.IdentityCommand do
  alias CoworkUser.Accounts
  alias CoworkUser.Kafka.IdentityCommandContract
  alias CoworkUser.Repo

  @result_error_code "USER_IDENTITY_REJECTED"
  @result_error_message "cowork-user could not commit the requested identity"

  def process(payload, key, result_topic) do
    case IdentityCommandContract.parse(payload, key) do
      {:ok, command} -> process_valid(command, result_topic)
      {:error, reason} -> {:reject, correlation_operation_id(payload), reason}
    end
  rescue
    exception -> {:retry, {:storage, exception}}
  end

  defp process_valid(command, result_topic) do
    command_hash = hash(IdentityCommandContract.canonical_payload(command))

    Repo.transaction(fn ->
      case lock_inbox(command) do
        nil -> apply_new(command, command_hash, result_topic)
        inbox -> replay_exact(inbox, command, command_hash, result_topic)
      end
    end)
    |> case do
      {:ok, :ok} ->
        :ok

      {:error, :conflicting_identity_command_reuse = reason} ->
        {:reject, command.operation_id, reason}

      {:error, reason} ->
        {:retry, {:storage, reason}}
    end
  end

  defp lock_inbox(command) do
    case Ecto.Adapters.SQL.query!(
           Repo,
           """
           SELECT operation_id, idempotency_key, user_id, command_hash, result_payload
           FROM tb_user_identity_command_inbox
           WHERE operation_id = ? OR idempotency_key = ?
           ORDER BY operation_id
           FOR UPDATE
           """,
           [command.operation_id, command.idempotency_key]
         ).rows do
      [] -> nil
      [row] -> row
      _rows -> Repo.rollback(:conflicting_identity_command_reuse)
    end
  end

  defp replay_exact(
         [operation_id, idempotency_key, user_id, stored_command_hash, result_payload],
         command,
         command_hash,
         result_topic
       ) do
    if operation_id == command.operation_id and idempotency_key == command.idempotency_key and
         user_id == command.user_id and stored_command_hash == command_hash do
      enqueue_result(result_topic, operation_id, encode_payload(result_payload))
    else
      Repo.rollback(:conflicting_identity_command_reuse)
    end
  end

  defp replay_exact(_inbox, _command, _command_hash, _result_topic) do
    Repo.rollback(:conflicting_identity_command_reuse)
  end

  defp apply_new(command, command_hash, result_topic) do
    {status, result} =
      case Accounts.apply_identity_command(command) do
        {:ok, user_id} ->
          {"SUCCEEDED", success_result(command.operation_id, user_id)}

        {:error, :validation} ->
          {"FAILED", failure_result(command.operation_id)}

        {:error, reason} ->
          Repo.rollback(reason)
      end

    payload = Jason.encode!(result)
    processed_at = DateTime.utc_now() |> DateTime.truncate(:microsecond)

    {1, nil} =
      Repo.insert_all("tb_user_identity_command_inbox", [
        %{
          operation_id: command.operation_id,
          idempotency_key: command.idempotency_key,
          user_id: command.user_id,
          command_hash: command_hash,
          result_status: status,
          result_payload: payload,
          result_hash: hash(payload),
          processed_at: processed_at
        }
      ])

    enqueue_result(result_topic, command.operation_id, payload)
  end

  defp enqueue_result(topic, operation_id, payload) do
    {1, nil} =
      Repo.insert_all("tb_kafka_outbox", [
        %{topic: topic, event_key: operation_id, payload: payload}
      ])

    :ok
  end

  defp success_result(operation_id, user_id) do
    %{
      schemaVersion: 1,
      operationId: operation_id,
      status: "SUCCEEDED",
      userId: user_id,
      occurredAt: DateTime.utc_now() |> DateTime.truncate(:microsecond) |> DateTime.to_iso8601()
    }
  end

  defp failure_result(operation_id) do
    %{
      schemaVersion: 1,
      operationId: operation_id,
      status: "FAILED",
      error: %{code: @result_error_code, message: @result_error_message},
      occurredAt: DateTime.utc_now() |> DateTime.truncate(:microsecond) |> DateTime.to_iso8601()
    }
  end

  defp hash(value) when is_binary(value),
    do: :crypto.hash(:sha256, value) |> Base.encode16(case: :lower)

  defp hash(value), do: value |> Jason.encode!() |> hash()

  defp encode_payload(payload) when is_binary(payload), do: payload
  defp encode_payload(payload), do: Jason.encode!(payload)

  defp correlation_operation_id(payload) do
    case IdentityCommandContract.correlation_operation_id(payload) do
      {:ok, operation_id} -> operation_id
      :error -> nil
    end
  end
end
