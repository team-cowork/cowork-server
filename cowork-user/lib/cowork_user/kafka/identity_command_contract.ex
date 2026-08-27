defmodule CoworkUser.Kafka.IdentityCommandContract do
  @schema_version 1
  @command_type "UPSERT"
  @max_idempotency_key_length 128

  @allowed_keys MapSet.new([
                  "schemaVersion",
                  "operationId",
                  "idempotencyKey",
                  "commandType",
                  "userId",
                  "name",
                  "email",
                  "sex",
                  "grade",
                  "classNumber",
                  "studentNumberInClass",
                  "major",
                  "role",
                  "githubId",
                  "dataGSMStudentId",
                  "requestedBy",
                  "occurredAt"
                ])

  def parse(payload, key) when is_map(payload) do
    with :ok <- exact_keys(payload),
         @schema_version <- payload["schemaVersion"],
         {:ok, operation_id} <- uuid(payload["operationId"]),
         {:ok, idempotency_key} <- idempotency_key(payload["idempotencyKey"]),
         @command_type <- payload["commandType"],
         {:ok, user_id} <- positive_integer(payload["userId"]),
         true <- to_string(key) == Integer.to_string(user_id),
         {:ok, requested_by} <- positive_integer(payload["requestedBy"]),
         true <- requested_by == user_id,
         {:ok, name} <- required_string(payload["name"], 50),
         {:ok, email} <- required_string(payload["email"], 255),
         {:ok, sex} <- required_string(payload["sex"], 10),
         {:ok, major} <- required_string(payload["major"], 50),
         {:ok, role} <- required_string(payload["role"], 50),
         {:ok, grade} <- optional_integer(payload["grade"]),
         {:ok, class_number} <- optional_integer(payload["classNumber"]),
         {:ok, student_number_in_class} <- optional_integer(payload["studentNumberInClass"]),
         {:ok, github_id} <- optional_string(payload["githubId"], 100),
         {:ok, datagsm_student_id} <- optional_positive_integer(payload["dataGSMStudentId"]),
         {:ok, occurred_at} <- occurred_at(payload["occurredAt"]) do
      {:ok,
       %{
         schema_version: @schema_version,
         operation_id: operation_id,
         idempotency_key: idempotency_key,
         command_type: @command_type,
         user_id: user_id,
         name: name,
         email: email,
         sex: sex,
         grade: grade,
         class_number: class_number,
         student_number_in_class: student_number_in_class,
         major: major,
         role: role,
         github_id: github_id,
         datagsm_student_id: datagsm_student_id,
         requested_by: requested_by,
         occurred_at: occurred_at
       }}
    else
      _ -> {:error, :invalid_user_identity_command}
    end
  end

  def parse(_payload, _key), do: {:error, :invalid_user_identity_command}

  @doc false
  def correlation_operation_id(%{"operationId" => operation_id}) do
    case uuid(operation_id) do
      {:ok, canonical} -> {:ok, canonical}
      _invalid -> :error
    end
  end

  def correlation_operation_id(_payload), do: :error

  def canonical_payload(command) do
    %{
      "schemaVersion" => command.schema_version,
      "operationId" => command.operation_id,
      "idempotencyKey" => command.idempotency_key,
      "commandType" => command.command_type,
      "userId" => command.user_id,
      "name" => command.name,
      "email" => command.email,
      "sex" => command.sex,
      "grade" => command.grade,
      "classNumber" => command.class_number,
      "studentNumberInClass" => command.student_number_in_class,
      "major" => command.major,
      "role" => command.role,
      "githubId" => command.github_id,
      "dataGSMStudentId" => command.datagsm_student_id,
      "requestedBy" => command.requested_by,
      "occurredAt" => DateTime.to_iso8601(command.occurred_at)
    }
  end

  defp exact_keys(payload) do
    if MapSet.new(Map.keys(payload)) == @allowed_keys, do: :ok, else: {:error, :unexpected_fields}
  end

  defp uuid(value) when is_binary(value) do
    case Ecto.UUID.cast(value) do
      {:ok, canonical} ->
        if canonical == value and value == String.downcase(value),
          do: {:ok, canonical},
          else: {:error, :invalid_uuid}

      _ ->
        {:error, :invalid_uuid}
    end
  end

  defp uuid(_value), do: {:error, :invalid_uuid}

  defp idempotency_key(value) when is_binary(value) do
    if String.trim(value) != "" and String.length(value) <= @max_idempotency_key_length,
      do: {:ok, value},
      else: {:error, :invalid_idempotency_key}
  end

  defp idempotency_key(_value), do: {:error, :invalid_idempotency_key}

  defp positive_integer(value) when is_integer(value) and value > 0, do: {:ok, value}
  defp positive_integer(_value), do: {:error, :invalid_positive_integer}

  defp optional_positive_integer(nil), do: {:ok, nil}
  defp optional_positive_integer(value), do: positive_integer(value)

  defp optional_integer(nil), do: {:ok, nil}
  defp optional_integer(value) when is_integer(value), do: {:ok, value}
  defp optional_integer(_value), do: {:error, :invalid_integer}

  defp required_string(value, max) when is_binary(value) do
    if String.trim(value) != "" and String.length(value) <= max,
      do: {:ok, value},
      else: {:error, :invalid_string}
  end

  defp required_string(_value, _max), do: {:error, :invalid_string}

  defp optional_string(nil, _max), do: {:ok, nil}

  defp optional_string(value, max) when is_binary(value) do
    if String.length(value) <= max, do: {:ok, value}, else: {:error, :invalid_string}
  end

  defp optional_string(_value, _max), do: {:error, :invalid_string}

  defp occurred_at(value) when is_binary(value) do
    case DateTime.from_iso8601(value) do
      {:ok, parsed, _offset} -> {:ok, DateTime.truncate(parsed, :microsecond)}
      _ -> {:error, :invalid_occurred_at}
    end
  end

  defp occurred_at(_value), do: {:error, :invalid_occurred_at}
end
