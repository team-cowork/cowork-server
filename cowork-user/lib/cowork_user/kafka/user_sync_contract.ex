defmodule CoworkUser.Kafka.UserSyncContract do
  @event_type "student.updated"

  @allowed_keys MapSet.new([
                  "event_type",
                  "event_id",
                  "event_index",
                  "occurred_at",
                  "email",
                  "name",
                  "sex",
                  "student_role",
                  "student_number",
                  "major",
                  "specialty",
                  "github_id",
                  "datagsm_student_id"
                ])

  def parse(payload, key) when is_map(payload) do
    with :ok <- exact_keys(payload),
         @event_type <- payload["event_type"],
         {:ok, _event_id} <- required_string(payload["event_id"], 255),
         {:ok, _event_index} <- non_negative_integer(payload["event_index"]),
         {:ok, _occurred_at} <- occurred_at(payload["occurred_at"]),
         {:ok, _email} <- required_string(payload["email"], 255),
         {:ok, _name} <- required_string(payload["name"], 50),
         {:ok, _sex} <- required_string(payload["sex"], 10),
         {:ok, _student_role} <- required_string(payload["student_role"], 50),
         {:ok, _student_number} <- optional_integer(payload["student_number"]),
         {:ok, _major} <- optional_string(payload["major"], 50),
         {:ok, _specialty} <- optional_string(payload["specialty"], 255),
         {:ok, _github_id} <- optional_string(payload["github_id"], 100),
         {:ok, student_id} <- positive_integer(payload["datagsm_student_id"]),
         true <- key == Integer.to_string(student_id) do
      {:ok, payload}
    else
      _reason -> {:error, :invalid_user_sync_contract}
    end
  end

  def parse(_payload, _key), do: {:error, :invalid_user_sync_contract}

  defp exact_keys(payload) do
    if MapSet.new(Map.keys(payload)) == @allowed_keys,
      do: :ok,
      else: {:error, :unexpected_fields}
  end

  defp positive_integer(value) when is_integer(value) and value > 0, do: {:ok, value}
  defp positive_integer(_value), do: {:error, :invalid_positive_integer}

  defp non_negative_integer(value) when is_integer(value) and value >= 0, do: {:ok, value}
  defp non_negative_integer(_value), do: {:error, :invalid_non_negative_integer}

  defp optional_integer(nil), do: {:ok, nil}
  defp optional_integer(value) when is_integer(value), do: {:ok, value}
  defp optional_integer(_value), do: {:error, :invalid_integer}

  defp required_string(value, max_length) when is_binary(value) do
    if String.trim(value) != "" and String.length(value) <= max_length,
      do: {:ok, value},
      else: {:error, :invalid_string}
  end

  defp required_string(_value, _max_length), do: {:error, :invalid_string}

  defp optional_string(nil, _max_length), do: {:ok, nil}

  defp optional_string(value, max_length) when is_binary(value) do
    if String.length(value) <= max_length,
      do: {:ok, value},
      else: {:error, :invalid_string}
  end

  defp optional_string(_value, _max_length), do: {:error, :invalid_string}

  defp occurred_at(value) when is_binary(value) do
    case DateTime.from_iso8601(value) do
      {:ok, parsed, _offset} -> {:ok, parsed}
      _invalid -> {:error, :invalid_occurred_at}
    end
  end

  defp occurred_at(_value), do: {:error, :invalid_occurred_at}
end
