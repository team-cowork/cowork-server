defmodule CoworkUser.Kafka.EventParser do
  def positive_integer(payload, camel_key, snake_key \\ nil) do
    value = Map.get(payload, camel_key) || (snake_key && Map.get(payload, snake_key))

    case value do
      value when is_integer(value) and value > 0 ->
        {:ok, value}

      value when is_binary(value) ->
        case Integer.parse(value) do
          {parsed, ""} when parsed > 0 -> {:ok, parsed}
          _ -> {:error, {:invalid_positive_integer, camel_key}}
        end

      _ ->
        {:error, {:invalid_positive_integer, camel_key}}
    end
  end

  def naive_datetime(payload, camel_key, snake_key \\ nil) do
    value = Map.get(payload, camel_key) || (snake_key && Map.get(payload, snake_key))

    with value when is_binary(value) <- value do
      case DateTime.from_iso8601(value) do
        {:ok, datetime, _offset} ->
          {:ok, datetime |> DateTime.to_naive() |> NaiveDateTime.truncate(:microsecond)}

        {:error, _reason} ->
          {:error, {:invalid_datetime, camel_key}}
      end
    else
      _ -> {:error, {:invalid_datetime, camel_key}}
    end
  end
end
