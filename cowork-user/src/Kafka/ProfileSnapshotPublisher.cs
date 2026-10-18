using Dapper;
using MySqlConnector;

namespace CoworkUser.Kafka;

/// <summary>
/// Periodically republishes every profile followed by per-partition completion markers, so
/// downstream projections can prove they caught up. Runs only while source projections are current.
/// </summary>
public sealed class ProfileSnapshotPublisher(AppConfig config, Producer producer, ProjectionReadiness readiness, ILogger<ProfileSnapshotPublisher> logger)
    : BackgroundService
{
    const string LockName = "cowork-user:profile-snapshot";
    const int PageSize = 500;
    const int RetryMs = 2_000;

    readonly SemaphoreSlim immediate = new(0, 1);

    /// <summary>Requests a snapshot now; repeated requests coalesce.</summary>
    public void PublishNow()
    {
        if (immediate.CurrentCount == 0)
        {
            try
            {
                immediate.Release();
            }
            catch (SemaphoreFullException)
            {
            }
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            var delay = await RunSnapshotAsync() ? config.KafkaProfileSnapshotIntervalMs : RetryMs;
            try
            {
                await immediate.WaitAsync(delay, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    async Task<bool> RunSnapshotAsync()
    {
        try
        {
            await using var lockConnection = await App.OpenAsync();
            switch (await lockConnection.ExecuteScalarAsync<long?>("SELECT GET_LOCK(@LockName, 0)", new { LockName }))
            {
                case 1: break;
                case 0:
                    logger.LogWarning("User profile snapshot postponed: :snapshot_lock_busy");
                    return false;
                default: throw new InvalidOperationException("unexpected snapshot lock result");
            }

            try
            {
                var postponed = await PublishSnapshotAsync(lockConnection);
                if (postponed is null) return true;
                logger.LogWarning("User profile snapshot postponed: {Reason}", postponed);
                return false;
            }
            finally
            {
                await lockConnection.ExecuteScalarAsync<long?>("SELECT RELEASE_LOCK(@LockName)", new { LockName });
            }
        }
        catch (Exception exception)
        {
            logger.LogError("User profile snapshot failed: {Reason}", exception.Message);
            return false;
        }
    }

    // MySQL drops a named lock with its session, so every write runs on the connection that holds it:
    // once that session is gone the writes fail instead of overlapping another instance's snapshot.
    static async Task<T> LockedTransactionAsync<T>(MySqlConnection lockConnection, Func<MySqlConnection, MySqlTransaction, Task<T>> work)
    {
        await using var transaction = await lockConnection.BeginTransactionAsync();
        var result = await work(lockConnection, transaction);
        await transaction.CommitAsync();
        return result;
    }

    async Task<string?> PublishSnapshotAsync(MySqlConnection lockConnection)
    {
        var topic = config.KafkaProfileTopic;
        if (!readiness.Current()) return ":source_projections_not_ready";
        var partitionCount = producer.PartitionCount(topic);
        if (partitionCount <= 0) return $"invalid partition count {partitionCount}";

        var snapshotId = Guid.NewGuid().ToString();
        long lastUserId = 0;
        while (true)
        {
            var page = await LockedTransactionAsync(lockConnection, async (connection, transaction) =>
            {
                var accounts = (await connection.QueryAsync<(long Id, string Name, string? Github, ulong Version, DateTime OccurredAt)>("""
                    SELECT id, name, github, profile_event_version, profile_event_occurred_at
                    FROM tb_accounts WHERE id > @lastUserId ORDER BY id ASC LIMIT @PageSize FOR UPDATE
                    """, new { lastUserId, PageSize }, transaction)).ToList();
                var ids = accounts.Select(a => a.Id).ToList();
                var nicknames = ids.Count == 0
                    ? []
                    : (await connection.QueryAsync<(long AccountId, string? Nickname)>(
                        "SELECT account_id, nickname FROM tb_profiles WHERE account_id IN @ids ORDER BY account_id FOR UPDATE", new { ids }, transaction))
                    .ToDictionary(p => p.AccountId, p => p.Nickname);

                var profiles = accounts.Select(a => new ProfileEventPublisher.ProfileState(
                    a.Id, a.Name, nicknames[a.Id], a.Github, a.Version, Iso8601.AsUtc(a.OccurredAt))).ToList();
                await ProfileEventPublisher.EnqueueUpsertsAsync(connection, transaction, profiles, topic);
                return ids;
            });

            if (page.Count < PageSize) break;
            lastUserId = page[^1];
        }

        if (!readiness.Current()) return ":source_projections_advanced_during_snapshot";
        await LockedTransactionAsync(lockConnection, async (connection, transaction) =>
        {
            await ProfileEventPublisher.EnqueueSnapshotCompleteAsync(connection, transaction, topic, partitionCount, snapshotId, Iso8601.UtcNow());
            return 0;
        });
        return null;
    }
}
