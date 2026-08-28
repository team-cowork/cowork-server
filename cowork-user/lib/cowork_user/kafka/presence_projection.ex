defmodule CoworkUser.Kafka.PresenceProjection do
  alias CoworkUser.Kafka.EventParser
  alias CoworkUser.Kafka.PresenceProjection.Storage

  @statuses ~w(online offline)

  def apply_event(payload, storage \\ Storage)

  def apply_event(payload, storage) when is_map(payload) do
    event_type = Map.get(payload, "eventType") || Map.get(payload, "event_type")
    status = Map.get(payload, "status")

    with true <- event_type == "STATUS_CHANGED" || {:error, :invalid_event_type},
         {:ok, user_id} <- EventParser.positive_integer(payload, "userId", "user_id"),
         true <- status in @statuses || {:error, :invalid_status},
         {:ok, occurred_at} <- EventParser.naive_datetime(payload, "occurredAt", "occurred_at") do
      storage.apply(%{user_id: user_id, status: status, occurred_at: occurred_at})
    end
  end

  def apply_event(_payload, _storage), do: {:error, :invalid_payload}

  def reconcile_account(user_id, storage \\ Storage), do: storage.apply_latest_to_account(user_id)

  @doc false
  def resolve_status(current_status, current_at, incoming_status, incoming_at) do
    case NaiveDateTime.compare(incoming_at, current_at) do
      :gt -> incoming_status
      :lt -> current_status
      :eq -> if incoming_status == "offline", do: "offline", else: current_status
    end
  end
end

defmodule CoworkUser.Kafka.PresenceProjection.Storage do
  alias CoworkUser.Repo

  @upsert_sql """
  INSERT INTO tb_user_presence_projections (user_id, status, event_occurred_at)
  VALUES (?, ?, ?)
  ON DUPLICATE KEY UPDATE
      status = IF(
          VALUES(event_occurred_at) > event_occurred_at
          OR (
              VALUES(event_occurred_at) = event_occurred_at
              AND VALUES(status) = 'offline'
              AND status = 'online'
          ),
          VALUES(status),
          status
      ),
      event_occurred_at = GREATEST(event_occurred_at, VALUES(event_occurred_at))
  """

  @apply_account_sql """
  UPDATE tb_accounts AS account
  JOIN tb_user_presence_projections AS presence ON presence.user_id = account.id
  SET account.status = presence.status,
      account.presence_updated_at = presence.event_occurred_at,
      account.last_modified_by = presence.user_id
  WHERE account.id = ?
    AND (
        presence.event_occurred_at > account.presence_updated_at
        OR (
            presence.event_occurred_at = account.presence_updated_at
            AND presence.status = 'offline'
            AND account.status = 'online'
        )
    )
  """

  def apply(event) do
    Repo.transaction(fn ->
      with {:ok, _result} <-
             Ecto.Adapters.SQL.query(Repo, @upsert_sql, [
               event.user_id,
               event.status,
               event.occurred_at
             ]),
           :ok <- apply_latest_to_account(event.user_id) do
        :ok
      else
        {:error, reason} -> Repo.rollback(reason)
      end
    end)
    |> case do
      {:ok, :ok} -> :ok
      {:error, reason} -> {:error, {:storage, reason}}
    end
  end

  def apply_latest_to_account(user_id) do
    case Ecto.Adapters.SQL.query(Repo, @apply_account_sql, [user_id]) do
      {:ok, _result} -> :ok
      {:error, reason} -> {:error, reason}
    end
  end

  @doc false
  def sql_contract, do: %{upsert: @upsert_sql, apply_account: @apply_account_sql}
end
