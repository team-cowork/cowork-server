defmodule CoworkUser.TeamClient do
  def get_member_ids(team_id) do
    url = System.get_env("COWORK_TEAM_URL", "http://localhost:8080")

    case Req.get(url: "#{url}/teams/#{team_id}/members") do
      {:ok, %{status: 200, body: members}} when is_list(members) ->
        {:ok, Enum.map(members, & &1["userId"]) |> Enum.reject(&is_nil/1)}

      {:ok, %{status: 200, body: _other}} ->
        {:error, :invalid_response_body}

      {:ok, %{status: status}} ->
        {:error, {:http_status, status}}

      {:error, exception} ->
        {:error, exception}
    end
  end
end
