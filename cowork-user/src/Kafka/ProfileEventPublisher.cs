using Dapper;
using MySqlConnector;

namespace CoworkUser.Kafka;

/// <summary>Writes user.profile.event.v2 records and snapshot markers to the transactional outbox.</summary>
public static class ProfileEventPublisher
{
    public sealed record ProfileState(long Id, string Name, string? Nickname, string? GithubId, ulong Version, DateTime OccurredAt);

    public static async Task EnqueueCurrentUpsertAsync(MySqlConnection connection, MySqlTransaction transaction, long userId)
    {
        var account = await connection.QuerySingleAsync<(long Id, string Name, string? Github, DateTime UpdatedAt, DateTime? DatagsmUpdatedAt, ulong Version, DateTime? OccurredAt)>("""
            SELECT id, name, github, updated_at, datagsm_updated_at, profile_event_version, profile_event_occurred_at
            FROM tb_accounts WHERE id = @userId FOR UPDATE
            """, new { userId }, transaction);
        var profile = await connection.QuerySingleAsync<(string? Nickname, DateTime UpdatedAt)>(
            "SELECT nickname, updated_at FROM tb_profiles WHERE account_id = @userId FOR UPDATE", new { userId }, transaction);

        var (occurredAt, version) = NextProfileEventState(
            account.OccurredAt is { } current ? Iso8601.AsUtc(current) : null, account.Version,
            [Iso8601.AsUtc(account.UpdatedAt), account.DatagsmUpdatedAt is { } datagsm ? Iso8601.AsUtc(datagsm) : null, Iso8601.AsUtc(profile.UpdatedAt), Iso8601.UtcNow()]);

        await connection.ExecuteAsync(
            "UPDATE tb_accounts SET profile_event_occurred_at = @occurredAt, profile_event_version = @version WHERE id = @userId",
            new { occurredAt, version, userId }, transaction);

        await EnqueueUpsertsAsync(connection, transaction,
            [new ProfileState(account.Id, account.Name, profile.Nickname, account.Github, version, occurredAt)], App.Config.KafkaProfileTopic);
    }

    public static async Task EnqueueUpsertsAsync(MySqlConnection connection, MySqlTransaction transaction, IReadOnlyList<ProfileState> profiles, string topic)
    {
        if (profiles.Count == 0) return;
        var inserted = await connection.ExecuteAsync(
            "INSERT INTO tb_kafka_outbox (topic, event_key, payload) VALUES (@topic, @key, @payload)",
            profiles.Select(profile => new { topic, key = profile.Id.ToString(), payload = UpsertPayload(profile) }), transaction);
        if (inserted != profiles.Count) throw new InvalidOperationException("Profile outbox insert count mismatch");
    }

    internal static string UpsertPayload(ProfileState profile)
    {
        if (profile.Version == 0) throw new ArgumentException("profile snapshot is missing its persisted event version");
        return Json.Encode(new
        {
            eventType = "UPSERT",
            userId = profile.Id,
            name = profile.Name,
            nickname = profile.Nickname,
            githubId = profile.GithubId,
            version = profile.Version,
            occurredAt = Iso8601.Format(profile.OccurredAt),
        });
    }

    public static async Task EnqueueSnapshotCompleteAsync(MySqlConnection connection, MySqlTransaction transaction, string topic, int partitionCount, string snapshotId, DateTime occurredAt)
    {
        var inserted = await connection.ExecuteAsync(
            "INSERT INTO tb_kafka_outbox (topic, event_key, partition_id, payload) VALUES (@topic, @key, @partition, @payload)",
            Enumerable.Range(0, partitionCount).Select(partition => new
            {
                topic,
                key = ProjectionBarrier.Key(partition),
                partition,
                payload = Json.Encode(ProjectionBarrier.Event(topic, partition, snapshotId, occurredAt, "cowork-user")),
            }), transaction);
        if (inserted != partitionCount) throw new InvalidOperationException("Snapshot marker insert count mismatch");
    }

    /// <summary>
    /// Advances the source version and a strictly increasing occurred-at used by downstream
    /// last-writer-wins projections.
    /// </summary>
    internal static (DateTime OccurredAt, ulong Version) NextProfileEventState(DateTime? currentAt, ulong currentVersion, IEnumerable<DateTime?> candidates)
    {
        if (currentVersion == ulong.MaxValue) throw new ArgumentException("invalid or exhausted profile event version state");
        var current = currentAt ?? DateTime.UnixEpoch;
        var candidate = candidates.OfType<DateTime>().Aggregate(current, (latest, value) => value > latest ? value : latest);
        var occurredAt = candidate > current ? candidate : current.AddTicks(10);
        return (Iso8601.TruncateMicros(occurredAt), currentVersion + 1);
    }
}
