defmodule CoworkUser.Kafka.ProfileSnapshotPublisher do
  use GenServer

  import Ecto.Query
  require Logger

  alias CoworkUser.Accounts.{Account, Profile}
  alias CoworkUser.Kafka.{Producer, ProfileEventPublisher, ProjectionReadiness}
  alias CoworkUser.Repo

  @lock_name "cowork-user:profile-snapshot"
  @page_size 500
  @snapshot_retry_ms 2_000

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  def publish_now do
    case Process.whereis(__MODULE__) do
      nil -> :ok
      _pid -> GenServer.cast(__MODULE__, :publish_now)
    end
  end

  @impl true
  def init(opts) do
    config = Keyword.fetch!(opts, :config)

    state = %{
      config: config,
      enabled: config.kafka_enabled,
      interval_ms: config.kafka_profile_snapshot_interval_ms,
      immediate_snapshot_pending: false
    }

    if state.enabled do
      send(self(), :publish_snapshot)
    end

    {:ok, state}
  end

  @impl true
  def handle_info(:publish_snapshot, state) do
    next_delay =
      case run_snapshot(state) do
        :ok -> state.interval_ms
        {:error, _reason} -> @snapshot_retry_ms
      end

    Process.send_after(self(), :publish_snapshot, next_delay)

    {:noreply, state}
  end

  def handle_info(:publish_snapshot_now, state) do
    case run_snapshot(state) do
      :ok ->
        {:noreply, %{state | immediate_snapshot_pending: false}}

      {:error, _reason} ->
        Process.send_after(self(), :publish_snapshot_now, @snapshot_retry_ms)
        {:noreply, state}
    end
  end

  @impl true
  def handle_cast(:publish_now, %{immediate_snapshot_pending: true} = state),
    do: {:noreply, state}

  def handle_cast(:publish_now, state) do
    send(self(), :publish_snapshot_now)
    {:noreply, %{state | immediate_snapshot_pending: true}}
  end

  defp publish_snapshot(state) do
    topic = state.config.kafka_profile_topic

    with true <- ProjectionReadiness.current?() || {:error, :source_projections_not_ready},
         {:ok, partition_count} when partition_count > 0 <- Producer.partition_count(topic) do
      snapshot_id = Ecto.UUID.generate()
      publish_pages(topic, 0)

      if ProjectionReadiness.current?() do
        {:ok, :ok} =
          Repo.transaction(fn ->
            ProfileEventPublisher.enqueue_snapshot_complete!(
              Repo,
              topic,
              0..(partition_count - 1),
              snapshot_id,
              DateTime.utc_now()
            )
          end)

        :ok
      else
        {:error, :source_projections_advanced_during_snapshot}
      end
    else
      {:ok, partition_count} -> {:error, {:invalid_partition_count, partition_count}}
      {:error, reason} -> {:error, reason}
    end
  end

  defp run_snapshot(state) do
    try do
      result =
        Repo.checkout(fn ->
          execute_with_lock(
            &acquire_snapshot_lock/0,
            &release_snapshot_lock/0,
            fn -> publish_snapshot(state) end
          )
        end)

      case result do
        :ok ->
          :ok

        {:error, reason} ->
          Logger.warning("User profile snapshot postponed: #{inspect(reason)}")
          {:error, reason}
      end
    rescue
      exception ->
        Logger.error("User profile snapshot failed: #{Exception.message(exception)}")
        {:error, :snapshot_failed}
    end
  end

  @doc false
  def execute_with_lock(acquire, release, publish_snapshot) do
    case acquire.() do
      :acquired ->
        try do
          publish_snapshot.()
        after
          :ok = release.()
        end

      :busy ->
        {:error, :snapshot_lock_busy}

      {:error, reason} ->
        {:error, reason}
    end
  end

  @doc false
  def acquire_result({:ok, %{rows: [[1]]}}), do: :acquired
  def acquire_result({:ok, %{rows: [[0]]}}), do: :busy

  def acquire_result({:ok, %{rows: rows}}),
    do: {:error, {:unexpected_snapshot_lock_result, rows}}

  def acquire_result({:error, reason}), do: {:error, {:snapshot_lock_query_failed, reason}}

  @doc false
  def release_result({:ok, %{rows: [[1]]}}), do: :ok

  def release_result({:ok, %{rows: rows}}),
    do: {:error, {:unexpected_snapshot_lock_release_result, rows}}

  def release_result({:error, reason}), do: {:error, {:snapshot_lock_release_failed, reason}}

  defp acquire_snapshot_lock do
    Repo
    |> Ecto.Adapters.SQL.query("SELECT GET_LOCK(?, 0)", [@lock_name])
    |> acquire_result()
  end

  defp release_snapshot_lock do
    Repo
    |> Ecto.Adapters.SQL.query("SELECT RELEASE_LOCK(?)", [@lock_name])
    |> release_result()
  end

  defp publish_pages(topic, last_user_id) do
    {:ok, page} =
      Repo.transaction(fn ->
        accounts =
          from(a in Account,
            where: a.id > ^last_user_id,
            order_by: [asc: a.id],
            limit: @page_size,
            lock: "FOR UPDATE",
            select: %{
              id: a.id,
              name: a.name,
              github_id: a.github,
              account_updated_at: a.updated_at,
              profile_event_version: a.profile_event_version,
              profile_event_occurred_at: a.profile_event_occurred_at
            }
          )
          |> Repo.all()

        account_ids = Enum.map(accounts, & &1.id)

        profiles_by_account =
          from(p in Profile,
            where: p.account_id in ^account_ids,
            order_by: [asc: p.account_id],
            lock: "FOR UPDATE",
            select: %{
              account_id: p.account_id,
              nickname: p.nickname,
              profile_updated_at: p.updated_at
            }
          )
          |> Repo.all()
          |> Map.new(&{&1.account_id, &1})

        page =
          Enum.map(accounts, fn account ->
            account
            |> Map.merge(Map.fetch!(profiles_by_account, account.id))
            |> Map.delete(:account_id)
          end)

        ProfileEventPublisher.enqueue_upserts!(Repo, page, topic)
        page
      end)

    if length(page) == @page_size do
      publish_pages(topic, List.last(page).id)
    else
      :ok
    end
  end
end
