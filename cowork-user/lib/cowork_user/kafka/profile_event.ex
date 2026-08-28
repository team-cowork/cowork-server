defmodule CoworkUser.Kafka.ProfileEvent do
  def upsert(profile) do
    upsert(
      profile,
      Map.get(
        profile,
        :profile_event_occurred_at,
        Map.get(profile, :event_occurred_at, DateTime.utc_now())
      ),
      Map.get(profile, :profile_event_version, 1)
    )
  end

  def upsert(profile, occurred_at) do
    upsert(profile, occurred_at, Map.get(profile, :profile_event_version, 1))
  end

  def upsert(profile, occurred_at, version) do
    %{
      eventType: "UPSERT",
      userId: profile.id,
      name: profile.name,
      nickname: profile.nickname,
      githubId: profile.github_id,
      version: version,
      occurredAt: DateTime.to_iso8601(occurred_at)
    }
  end

  def delete(user_id, occurred_at \\ DateTime.utc_now(), version \\ 1) do
    %{
      eventType: "DELETE",
      userId: user_id,
      name: nil,
      nickname: nil,
      githubId: nil,
      version: version,
      occurredAt: DateTime.to_iso8601(occurred_at)
    }
  end
end
