defmodule CoworkUser.JSON do
  import Plug.Conn

  def send(conn, status, body) do
    conn
    |> put_resp_content_type("application/json")
    |> send_resp(status, Jason.encode!(body))
  end

  def error(conn, status, message), do: send(conn, status, %{message: message})
end
