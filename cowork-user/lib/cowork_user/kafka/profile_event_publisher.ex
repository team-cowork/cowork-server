defmodule CoworkUser.Kafka.ProfileEventPublisher do
  import Ecto.Query

  alias CoworkUser.Accounts.{Account, Profile}
  alias CoworkUser.Kafka.{ProfileEvent, ProjectionBarrier}

  def enqueue_current_upsert!(repo, user_id) do
    account =
      from(a in Account,
        where: a.id == ^user_id,
        lock: "FOR UPDATE",
        select: %{
          id: a.id,
          name: a.name,
          github_id: a.github,
          account_updated_at: a.updated_at
        }
      )
      |> repo.one!()

    profile =
      from(p in Profile,
        where: p.account_id == ^user_id,
        lock: "FOR UPDATE",
        select: %{
          nickname: p.nickname,
          profile_updated_at: p.updated_at
        }
      )
      |> repo.one!()

    enqueue_upserts!(repo, [Map.merge(account, profile)])
  end

  def enqueue_upserts!(repo, profiles) do
    enqueue_upserts!(repo, profiles, CoworkUser.AppConfig.load().kafka_profile_topic)
  end

  def enqueue_upserts!(_repo, [], _topic), do: :ok

  def enqueue_upserts!(repo, profiles, topic) do
    rows = outbox_rows(topic, profiles)

    {count, nil} = repo.insert_all("tb_kafka_outbox", rows)
    true = count == length(rows)
    :ok
  end

  @doc false
  def outbox_rows(topic, profiles) do
    Enum.map(profiles, fn profile ->
      occurred_at = latest(profile.account_updated_at, profile.profile_updated_at)
      event = ProfileEvent.upsert(profile, occurred_at)

      %{
        topic: topic,
        event_key: to_string(profile.id),
        payload: Jason.encode!(event)
      }
    end)
  end

  def enqueue_snapshot_complete!(repo, topic, partitions, snapshot_id, occurred_at) do
    rows = snapshot_barrier_rows(topic, partitions, snapshot_id, occurred_at)
    {count, nil} = repo.insert_all("tb_kafka_outbox", rows)
    true = count == length(rows)
    :ok
  end

  @doc false
  def snapshot_barrier_rows(topic, partitions, snapshot_id, occurred_at) do
    Enum.map(partitions, fn partition ->
      event = ProjectionBarrier.event(topic, partition, snapshot_id, occurred_at, "cowork-user")

      %{
        topic: topic,
        event_key: ProjectionBarrier.key(partition),
        partition_id: partition,
        payload: Jason.encode!(event)
      }
    end)
  end

  def enqueue_delete!(repo, user_id, occurred_at \\ DateTime.utc_now()) do
    topic = CoworkUser.AppConfig.load().kafka_profile_topic
    event = ProfileEvent.delete(user_id, occurred_at)

    {1, nil} =
      repo.insert_all("tb_kafka_outbox", [
        %{
          topic: topic,
          event_key: to_string(user_id),
          payload: Jason.encode!(event)
        }
      ])

    :ok
  end

  defp latest(left, right) do
    case DateTime.compare(left, right) do
      :lt -> right
      _ -> left
    end
  end
end
