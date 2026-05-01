defmodule CoworkUser.Router do
  use Plug.Router
  import Plug.Conn

  alias CoworkUser.Accounts
  alias CoworkUser.JSON
  alias CoworkUser.Metrics
  alias CoworkUser.OpenAPI

  plug Plug.RequestId
  plug Plug.Logger
  plug :measure_request
  plug :match
  plug Plug.Parsers, parsers: [:json], pass: ["application/json"], json_decoder: Jason
  plug :dispatch

  get "/actuator/health" do
    JSON.send(conn, 200, %{status: "UP"})
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
      {:error, message} when is_binary(message) -> JSON.error(conn, 400, message)
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
    JSON.send(conn, 200, Accounts.search_users(conn.params))
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

  put "/users/:user_id" do
    with {:ok, user_id} <- parse_integer(user_id, "user_id"),
         {:ok, profile} <- Accounts.upsert_user(user_id, conn.body_params) do
      JSON.send(conn, 200, profile)
    else
      {:error, {:validation, message}} -> JSON.error(conn, 400, message)
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

  defp measure_request(conn, _opts) do
    start = System.monotonic_time()

    Plug.Conn.register_before_send(conn, fn conn ->
      duration = System.monotonic_time() - start
      Metrics.record(conn.request_path, conn.status, duration)
      conn
    end)
  end
end
