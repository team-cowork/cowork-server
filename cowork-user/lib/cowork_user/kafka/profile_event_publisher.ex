defmodule CoworkUser.Kafka.ProfileEventPublisher do
  import Ecto.Query

  alias CoworkUser.Accounts.{Account, Profile}
  alias CoworkUser.Kafka.{ProfileEvent, ProjectionBarrier}

  @profile_event_epoch ~U[1970-01-01 00:00:00.000000Z]
  @max_profile_event_version 18_446_744_073_709_551_615

  def enqueue_current_upserts!(_repo, []), do: :ok

  def enqueue_current_upserts!(repo, user_ids) do
    profiles = Enum.map(user_ids, &advance_current_profile_state!(repo, &1))
    enqueue_upserts!(repo, profiles)
  end

  def advance_current_states!(_repo, []), do: :ok

  def advance_current_states!(repo, user_ids) do
    Enum.each(user_ids, &advance_current_profile_state!(repo, &1))
    :ok
  end

  def enqueue_current_upsert!(repo, user_id) do
    enqueue_current_upserts!(repo, [user_id])
  end

  defp advance_current_profile_state!(repo, user_id) do
    account =
      from(a in Account,
        where: a.id == ^user_id,
        lock: "FOR UPDATE",
        select: %{
          id: a.id,
          name: a.name,
          github_id: a.github,
          account_updated_at: a.updated_at,
          datagsm_updated_at: a.datagsm_updated_at,
          profile_event_version: a.profile_event_version,
          profile_event_occurred_at: a.profile_event_occurred_at
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

    {occurred_at, version} =
      next_profile_event_state(
        account.profile_event_occurred_at,
        account.profile_event_version,
        [
          account.account_updated_at,
          account.datagsm_updated_at,
          profile.profile_updated_at,
          DateTime.utc_now()
        ]
      )

    {1, nil} =
      from(a in Account, where: a.id == ^user_id)
      |> repo.update_all(
        set: [
          profile_event_occurred_at: occurred_at,
          profile_event_version: version
        ]
      )

    account
    |> Map.merge(profile)
    |> Map.put(:profile_event_occurred_at, occurred_at)
    |> Map.put(:profile_event_version, version)
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
      {occurred_at, version} = current_profile_event_state(profile)
      event = ProfileEvent.upsert(profile, occurred_at, version)

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

  @doc false
  def next_profile_event_state(current_at, current_version, candidates)
      when is_integer(current_version) and current_version >= 0 and
             current_version < @max_profile_event_version and is_list(candidates) do
    current_at = current_at || @profile_event_epoch

    candidate =
      candidates
      |> Enum.filter(&match?(%DateTime{}, &1))
      |> Enum.reduce(current_at, fn candidate, latest ->
        if DateTime.compare(candidate, latest) == :gt, do: candidate, else: latest
      end)

    occurred_at =
      if DateTime.compare(candidate, current_at) == :gt do
        candidate
      else
        DateTime.add(current_at, 1, :microsecond)
      end

    {DateTime.truncate(occurred_at, :microsecond), current_version + 1}
  end

  def next_profile_event_state(_current_at, _current_version, _candidates) do
    raise ArgumentError, "invalid or exhausted profile event version state"
  end

  @doc false
  def current_profile_event_state(profile) do
    case {Map.fetch(profile, :profile_event_occurred_at),
          Map.fetch(profile, :profile_event_version)} do
      {{:ok, %DateTime{} = occurred_at}, {:ok, version}}
      when is_integer(version) and version > 0 ->
        {occurred_at, version}

      _other ->
        raise ArgumentError, "profile snapshot is missing its persisted event version"
    end
  end
end
