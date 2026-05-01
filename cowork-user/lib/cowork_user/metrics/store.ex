defmodule CoworkUser.Metrics.Store do
  use Agent

  def start_link(_opts) do
    Agent.start_link(fn -> %{} end, name: __MODULE__)
  end

  def record(uri, status, duration_native) do
    Agent.update(__MODULE__, fn state ->
      key = {normalize_uri(uri), Integer.to_string(status)}
      previous = Map.get(state, key, %{count: 0, sum_seconds: 0.0})

      Map.put(state, key, %{
        count: previous.count + 1,
        sum_seconds: previous.sum_seconds + System.convert_time_unit(duration_native, :native, :microsecond) / 1_000_000
      })
    end)
  end

  def snapshot, do: Agent.get(__MODULE__, & &1)

  defp normalize_uri(path) do
    Regex.replace(~r{/\d+}, path, "/:id")
  end
end
