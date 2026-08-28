defmodule CoworkUser.Kafka.ProjectionReadinessGateTest do
  use ExUnit.Case, async: true
  import Plug.Test

  alias CoworkUser.Kafka.ProjectionReadinessGate

  test "projection-dependent public reads fail closed while presence is catching up" do
    for path <- [
          "/users/me",
          "/users/batch?ids=1",
          "/users/42",
          "/users/by-github/octocat",
          "/users/search"
        ] do
      conn =
        :get
        |> conn(path)
        |> ProjectionReadinessGate.call(ProjectionReadinessGate.init(ready?: fn -> false end))

      assert conn.halted
      assert conn.status == 503
    end
  end

  test "projection-dependent reads pass after readiness opens" do
    conn =
      :get
      |> conn("/users/42")
      |> ProjectionReadinessGate.call(ProjectionReadinessGate.init(ready?: fn -> true end))

    refute conn.halted
    assert is_nil(conn.status)
  end

  test "operational routes and writes stay exempt" do
    requests = [
      {:get, "/actuator/health"},
      {:get, "/actuator/prometheus"},
      {:get, "/v3/api-docs"},
      {:patch, "/users/me"}
    ]

    for {method, path} <- requests do
      conn =
        method
        |> conn(path)
        |> ProjectionReadinessGate.call(ProjectionReadinessGate.init(ready?: fn -> false end))

      refute conn.halted
      assert is_nil(conn.status)
    end
  end

  test "readiness process failure also fails closed" do
    conn =
      :get
      |> conn("/users/me")
      |> ProjectionReadinessGate.call(
        ProjectionReadinessGate.init(ready?: fn -> exit(:not_started) end)
      )

    assert conn.halted
    assert conn.status == 503
  end
end
