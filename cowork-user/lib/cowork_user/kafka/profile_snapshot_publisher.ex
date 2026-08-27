defmodule CoworkUser.Kafka.ProfileSnapshotPublisher do
  use GenServer

  import Ecto.Query
  require Logger

  alias CoworkUser.Accounts.{Account, Profile}
  alias CoworkUser.Kafka.{Producer, ProfileEventPublisher}
  alias CoworkUser.Repo

  @page_size 500
  @snapshot_retry_ms 2_000

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  @impl true
  def init(opts) do
    config = Keyword.fetch!(opts, :config)

    state = %{
      config: config,
      enabled: config.kafka_enabled,
      interval_ms: config.kafka_profile_snapshot_interval_ms
    }

    if state.enabled do
      send(self(), :publish_snapshot)
    end

    {:ok, state}
  end

  @impl true
  def handle_info(:publish_snapshot, state) do
    next_delay =
      try do
        case publish_snapshot(state) do
          :ok ->
            state.interval_ms

          {:error, reason} ->
            Logger.warning("User profile snapshot postponed: #{inspect(reason)}")
            @snapshot_retry_ms
        end
      rescue
        exception ->
          Logger.error("User profile snapshot failed: #{Exception.message(exception)}")
          @snapshot_retry_ms
      end

    Process.send_after(self(), :publish_snapshot, next_delay)

    {:noreply, state}
  end

  defp publish_snapshot(state) do
    topic = state.config.kafka_profile_topic

    with {:ok, partition_count} when partition_count > 0 <- Producer.partition_count(topic) do
      snapshot_id = Ecto.UUID.generate()
      publish_pages(topic, 0)

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
      {:ok, partition_count} -> {:error, {:invalid_partition_count, partition_count}}
      {:error, reason} -> {:error, reason}
    end
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
              account_updated_at: a.updated_at
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
