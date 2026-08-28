defmodule CoworkUser.Router do
  use Plug.Router
  import Plug.Conn

  alias CoworkUser.Accounts
  alias CoworkUser.JSON
  alias CoworkUser.Metrics
  alias CoworkUser.OpenAPI

  plug(Plug.RequestId)
  plug(Plug.Logger)
  plug(:measure_request)
  plug(:match)
  plug(Plug.Parsers, parsers: [:json], pass: ["application/json"], json_decoder: Jason)
  plug(CoworkUser.Kafka.ProjectionReadinessGate)
  plug(:dispatch)

  get "/actuator/health" do
    JSON.send(conn, 200, %{status: "UP"})
  end

  get "/actuator/health/readiness" do
    if CoworkUser.Kafka.ProjectionReadiness.ready?() do
      JSON.send(conn, 200, %{status: "UP"})
    else
      JSON.send(conn, 503, %{status: "OUT_OF_SERVICE"})
    end
  end

  get "/actuator/prometheus" do
    conn
    |> put_resp_content_type("text/plain; version=0.0.4")
    |> send_resp(200, Metrics.render_prometheus())
  end

  get "/v3/api-docs" do
    JSON.send(conn, 200, OpenAPI.spec())
  end

  get "/swagger-ui.html" do
    conn
    |> put_resp_content_type("text/html")
    |> send_resp(200, OpenAPI.swagger_ui_html())
  end

  get "/users/me" do
    with {:ok, user_id} <- user_id_from_header(conn),
         {:ok, profile} <- Accounts.get_my_profile(user_id) do
      JSON.send(conn, 200, profile)
    else
      {:error, :not_found} -> JSON.error(conn, 404, "사용자를 찾을 수 없습니다.")
      {:error, :missing_user_id} -> JSON.error(conn, 400, "X-User-Id 헤더가 누락되었습니다.")
      {:error, {:validation, message}} -> JSON.error(conn, 400, message)
    end
  end

  patch "/users/me" do
    with {:ok, user_id} <- user_id_from_header(conn),
         {:ok, profile} <- Accounts.update_my_profile(user_id, conn.body_params) do
      JSON.send(conn, 200, profile)
    else
      {:error, :not_found} -> JSON.error(conn, 404, "사용자를 찾을 수 없습니다.")
      {:error, :missing_user_id} -> JSON.error(conn, 400, "X-User-Id 헤더가 누락되었습니다.")
      {:error, {:validation, message}} -> JSON.error(conn, 400, message)
    end
  end

  patch "/users/me/status" do
    with {:ok, user_id} <- user_id_from_header(conn),
         {:ok, profile} <- Accounts.update_my_status(user_id, conn.body_params) do
      JSON.send(conn, 200, profile)
    else
      {:error, :not_found} -> JSON.error(conn, 404, "사용자를 찾을 수 없습니다.")
      {:error, :missing_user_id} -> JSON.error(conn, 400, "X-User-Id 헤더가 누락되었습니다.")
      {:error, {:validation, message}} -> JSON.error(conn, 400, message)
    end
  end

  post "/users/me/profile-image/presigned" do
    with {:ok, user_id} <- user_id_from_header(conn),
         {:ok, response} <- Accounts.generate_presigned_url(user_id, conn.body_params) do
      JSON.send(conn, 200, response)
    else
      {:error, :missing_user_id} -> JSON.error(conn, 400, "X-User-Id 헤더가 누락되었습니다.")
      {:error, :not_found} -> JSON.error(conn, 404, "사용자를 찾을 수 없습니다.")
      {:error, {:validation, message}} -> JSON.error(conn, 400, message)
    end
  end

  post "/users/me/profile-image/confirm" do
    with {:ok, user_id} <- user_id_from_header(conn),
         :ok <- Accounts.confirm_upload(user_id, conn.body_params) do
      send_resp(conn, 200, "")
    else
      {:error, :missing_user_id} -> JSON.error(conn, 400, "X-User-Id 헤더가 누락되었습니다.")
      {:error, :not_found} -> JSON.error(conn, 404, "사용자를 찾을 수 없습니다.")
      {:error, {:validation, message}} -> JSON.error(conn, 400, message)
      {:error, {:conflict, message}} -> JSON.error(conn, 409, message)
      {:error, {:payload_too_large, message}} -> JSON.error(conn, 413, message)
    end
  end

  delete "/users/me/profile-image" do
    with {:ok, user_id} <- user_id_from_header(conn),
         :ok <- Accounts.delete_profile_image(user_id) do
      send_resp(conn, 204, "")
    else
      {:error, :missing_user_id} -> JSON.error(conn, 400, "X-User-Id 헤더가 누락되었습니다.")
      {:error, :not_found} -> JSON.error(conn, 404, "사용자를 찾을 수 없습니다.")
    end
  end

  get "/users/search" do
    if Map.has_key?(conn.params, "teamId") and
         not CoworkUser.Kafka.ProjectionReadiness.team_ready?() do
      JSON.error(conn, 503, "팀 멤버 투영을 동기화하는 중입니다.")
    else
      with {:ok, user_id} <- user_id_from_header(conn),
           {:ok, result} <- Accounts.search_users(user_id, conn.params) do
        JSON.send(conn, 200, result)
      else
        {:error, :missing_user_id} ->
          JSON.error(conn, 400, "X-User-Id 헤더가 누락되었습니다.")

        {:error, :forbidden} ->
          JSON.error(conn, 403, "해당 팀의 멤버만 사용자를 검색할 수 있습니다.")

        {:error, {:validation, message}} ->
          JSON.error(conn, 400, message)

        {:error, {:team_projection, _}} ->
          JSON.error(conn, 503, "팀 멤버 투영을 조회할 수 없습니다.")
      end
    end
  end

  get "/users/batch" do
    with {:ok, ids} <- parse_ids(conn.params["ids"]),
         {:ok, users} <- Accounts.get_display_names(ids) do
      JSON.send(conn, 200, %{users: users})
    else
      {:error, {:validation, message}} -> JSON.error(conn, 400, message)
    end
  end

  get "/users/:user_id" do
    with {:ok, user_id} <- parse_integer(user_id, "user_id"),
         {:ok, profile} <- Accounts.get_user_profile(user_id) do
      JSON.send(conn, 200, profile)
    else
      {:error, :not_found} -> JSON.error(conn, 404, "사용자를 찾을 수 없습니다.")
      {:error, {:validation, message}} -> JSON.error(conn, 400, message)
    end
  end

  get "/users/by-github/:github_username" do
    case Accounts.get_by_github(github_username) do
      {:ok, profile} -> JSON.send(conn, 200, profile)
      {:error, :not_found} -> JSON.error(conn, 404, "사용자를 찾을 수 없습니다.")
    end
  end

  match _ do
    JSON.error(conn, 404, "요청한 경로를 찾을 수 없습니다.")
  end

  defp user_id_from_header(conn) do
    case Plug.Conn.get_req_header(conn, "x-user-id") do
      [value | _] -> parse_integer(value, "X-User-Id")
      [] -> {:error, :missing_user_id}
    end
  end

  defp parse_integer(value, field_name) do
    case Integer.parse(to_string(value)) do
      {parsed, ""} -> {:ok, parsed}
      _ -> {:error, {:validation, "#{field_name} 값이 올바르지 않습니다."}}
    end
  end

  defp parse_ids(nil), do: {:error, {:validation, "ids 파라미터가 필요합니다."}}

  defp parse_ids(ids_str) when is_binary(ids_str) do
    ids =
      ids_str
      |> Accounts.parse_int_csv()
      |> Enum.uniq()
      |> Enum.take(100)

    case ids do
      [] -> {:error, {:validation, "유효한 ids가 없습니다."}}
      _ -> {:ok, ids}
    end
  end

  defp parse_ids(_invalid), do: {:error, {:validation, "ids 파라미터 형식이 올바르지 않습니다."}}

  defp measure_request(conn, _opts) do
    start = System.monotonic_time()

    Plug.Conn.register_before_send(conn, fn conn ->
      duration = System.monotonic_time() - start
      Metrics.record(conn.request_path, conn.status, duration)
      conn
    end)
  end
end
