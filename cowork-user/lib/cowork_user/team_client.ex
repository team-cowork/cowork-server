defmodule CoworkUser.TeamClient do
  def get_member_ids(team_id) do
    case Integer.parse(to_string(team_id)) do
      {id, ""} when id > 0 ->
        url = System.get_env("COWORK_TEAM_URL", "http://localhost:8080")

        case Req.get(url: "#{url}/teams/#{id}/members", receive_timeout: 5_000) do
          {:ok, %{status: 200, body: members}} when is_list(members) ->
            {:ok,
             Enum.flat_map(members, fn m ->
               case m["userId"] do
                 uid when is_integer(uid) and uid > 0 -> [uid]
                 _ -> []
               end
             end)}

          {:ok, %{status: 200, body: _other}} ->
            {:error, :invalid_response_body}

          {:ok, %{status: status}} ->
            {:error, {:http_status, status}}

          {:error, exception} ->
            {:error, exception}
        end

      _ ->
        {:error, :invalid_team_id}
    end
  end
end
