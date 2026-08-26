defmodule CoworkUser.Kafka.ProjectionReadinessGate do
  @behaviour Plug

  import Plug.Conn

  alias CoworkUser.JSON
  alias CoworkUser.Kafka.ProjectionReadiness

  @impl true
  def init(opts) do
    Keyword.get(opts, :ready?, &ProjectionReadiness.ready?/0)
  end

  @impl true
  def call(conn, ready?) when is_function(ready?, 0) do
    if projection_dependent_read?(conn.method, conn.request_path) and not safely_ready?(ready?) do
      conn
      |> JSON.error(503, "사용자 상태 투영을 동기화하는 중입니다.")
      |> halt()
    else
      conn
    end
  end

  @doc false
  def projection_dependent_read?("GET", "/users/me"), do: true
  def projection_dependent_read?("GET", "/users/search"), do: true

  def projection_dependent_read?("GET", "/users/by-github/" <> username),
    do: username != ""

  def projection_dependent_read?("GET", "/users/" <> user_id) do
    case Integer.parse(user_id) do
      {parsed, ""} when parsed > 0 -> true
      _invalid -> false
    end
  end

  def projection_dependent_read?(_method, _path), do: false

  defp safely_ready?(ready?) do
    ready?.()
  rescue
    _exception -> false
  catch
    :exit, _reason -> false
  end
end
