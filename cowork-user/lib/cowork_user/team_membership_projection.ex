defmodule CoworkUser.TeamMembershipProjection do
  alias CoworkUser.Kafka.EventParser
  alias CoworkUser.TeamMembershipProjection.Storage

  @event_types ~w(UPSERT DELETE)
  @roles ~w(OWNER ADMIN MEMBER)

  def apply_event(payload, storage \\ Storage)

  def apply_event(payload, storage) when is_map(payload) do
    with {:ok, event} <- parse_event(payload) do
      storage.apply(event)
    end
  end

  def apply_event(_payload, _storage), do: {:error, :invalid_payload}

  def member_ids_for_requester(team_id, requester_user_id, storage \\ Storage) do
    with {:ok, parsed_team_id} <- parse_positive_id(team_id, :invalid_team_id),
         {:ok, parsed_requester_user_id} <-
           parse_positive_id(requester_user_id, :invalid_requester_user_id) do
      storage.member_ids_for_requester(parsed_team_id, parsed_requester_user_id)
    end
  end

  defp parse_event(payload) do
    event_type = Map.get(payload, "eventType") || Map.get(payload, "event_type")
    role = Map.get(payload, "role")
    team_name = Map.get(payload, "teamName") || Map.get(payload, "team_name")

    with true <- event_type in @event_types || {:error, :invalid_event_type},
         {:ok, team_id} <- EventParser.positive_integer(payload, "teamId", "team_id"),
         {:ok, user_id} <- EventParser.positive_integer(payload, "userId", "user_id"),
         true <- role in @roles || {:error, :invalid_role},
         true <- (is_binary(team_name) and team_name != "") || {:error, :invalid_team_name},
         {:ok, occurred_at} <- EventParser.naive_datetime(payload, "occurredAt", "occurred_at") do
      {:ok,
       %{
         event_type: event_type,
         team_id: team_id,
         user_id: user_id,
         role: role,
         team_name: team_name,
         occurred_at: occurred_at
       }}
    end
  end

  defp parse_positive_id(value, error) do
    case Integer.parse(to_string(value)) do
      {id, ""} when id > 0 -> {:ok, id}
      _ -> {:error, error}
    end
  end
end

defmodule CoworkUser.TeamMembershipProjection.Storage do
  alias CoworkUser.Repo

  @apply_sql """
  INSERT INTO tb_team_member_projections
      (team_id, user_id, role, team_name, active, event_occurred_at)
  VALUES (?, ?, ?, ?, ?, ?)
  ON DUPLICATE KEY UPDATE
      role = IF(VALUES(event_occurred_at) >= event_occurred_at, VALUES(role), role),
      team_name = IF(VALUES(event_occurred_at) >= event_occurred_at, VALUES(team_name), team_name),
      active = IF(
          VALUES(event_occurred_at) > event_occurred_at,
          VALUES(active),
          IF(VALUES(event_occurred_at) = event_occurred_at, active AND VALUES(active), active)
      ),
      event_occurred_at = GREATEST(event_occurred_at, VALUES(event_occurred_at))
  """

  def apply(event) do
    active = event.event_type == "UPSERT"

    case Ecto.Adapters.SQL.query(Repo, @apply_sql, [
           event.team_id,
           event.user_id,
           event.role,
           event.team_name,
           active,
           event.occurred_at
         ]) do
      {:ok, _result} -> :ok
      {:error, reason} -> {:error, {:storage, reason}}
    end
  end

  def member_ids_for_requester(team_id, requester_user_id) do
    case Ecto.Adapters.SQL.query(
           Repo,
           """
           SELECT member.user_id
           FROM tb_team_member_projections AS member
           WHERE member.team_id = ?
             AND member.active = TRUE
             AND EXISTS (
                 SELECT 1
                 FROM tb_team_member_projections AS requester
                 WHERE requester.team_id = member.team_id
                   AND requester.user_id = ?
                   AND requester.active = TRUE
             )
           ORDER BY member.user_id
           """,
           [team_id, requester_user_id]
         ) do
      {:ok, %{rows: []}} -> {:error, :forbidden}
      {:ok, %{rows: rows}} -> {:ok, Enum.map(rows, fn [user_id] -> user_id end)}
      {:error, reason} -> {:error, {:storage, reason}}
    end
  end
end
