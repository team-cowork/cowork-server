using Dapper;
using MySqlConnector;

namespace CoworkUser.Kafka;

/// <summary>
/// Durable projection offsets with replay fencing. Every runtime start bumps a replay generation,
/// and every partition assignment replays from the earliest retained offset under a lease, so a
/// revoked worker can never advance a newer assignment's checkpoint.
/// </summary>
public static class ProjectionCheckpoint
{
    public sealed record Lease(string ConsumerGroup, string Topic, int Partition, long Generation, string Token, string Owner, long BeginningOffset);

    public sealed class LeaseBusy() : Exception(":assignment_replay_lease_busy");

    public sealed record State(
        long NextOffset, long Generation, string? Token, long? InvalidRecordOffset, long? SnapshotCompletedOffset,
        string? LastSnapshotId, string? RecoverySnapshotId, bool LeaseActive);

    public sealed record RecoveryState(long? InvalidRecordOffset, long? SnapshotCompletedOffset, string? LastSnapshotId, string? RecoverySnapshotId);

    /// <summary>A projection source with the topic UUID the broker reports now and how to clear what it derived.</summary>
    public sealed record ReplaySource(string Group, string Topic, string? TopicId, Func<MySqlConnection, MySqlTransaction, Task> ResetProjection);

    /// <summary>
    /// Bumps the replay generation of each source. A source whose topic UUID cannot be shown to match the stored one
    /// is rebuilt in the same transaction: its projection data, checkpoints and markers are cleared, because
    /// replacing a topic can drop deletion history that replaying the new log would never mention.
    /// Returns the topics that were rebuilt.
    /// </summary>
    public static Task<List<string>> StartReplayGenerationAsync(IEnumerable<ReplaySource> sources) =>
        App.TransactionAsync(async (connection, transaction) =>
        {
            var rebuilt = new List<string>();
            foreach (var source in sources.DistinctBy(s => (s.Group, s.Topic)))
            {
                var key = new { group = source.Group, topic = source.Topic, topicId = source.TopicId };
                var stored = await connection.ExecuteScalarAsync<string?>(
                    "SELECT topic_id FROM tb_kafka_projection_generations WHERE consumer_group = @group AND topic_name = @topic FOR UPDATE",
                    key, transaction);
                if (!TopicContinuous(stored, source.TopicId))
                {
                    await source.ResetProjection(connection, transaction);
                    await connection.ExecuteAsync(
                        "DELETE FROM tb_kafka_projection_offsets WHERE consumer_group = @group AND topic_name = @topic", key, transaction);
                    await connection.ExecuteAsync(
                        "DELETE FROM tb_kafka_projection_barriers WHERE consumer_group = @group AND topic_name = @topic", key, transaction);
                    rebuilt.Add(source.Topic);
                }

                await connection.ExecuteAsync("""
                    INSERT INTO tb_kafka_projection_generations (consumer_group, topic_name, replay_generation, topic_id)
                    VALUES (@group, @topic, 1, @topicId)
                    ON DUPLICATE KEY UPDATE
                        replay_generation = replay_generation + 1,
                        topic_id = COALESCE(@topicId, topic_id),
                        updated_at = CURRENT_TIMESTAMP(6)
                    """, key, transaction);
            }
            return rebuilt;
        });

    /// <summary>
    /// Startup proof of continuity: the stored projection continues the current topic only when both UUIDs are
    /// known and equal. A broker that reports no UUID, or a missing stored UUID, proves nothing and forces a rebuild.
    /// </summary>
    internal static bool TopicContinuous(string? stored, string? current) => current is not null && stored == current;

    /// <summary>
    /// Runtime detection of a replaced topic: the broker reports a UUID that differs from the stored one. Without a
    /// reported UUID there is nothing to detect, so a broker that never reports one cannot loop the replay.
    /// </summary>
    internal static bool TopicReplaced(string? stored, string? current) => current is not null && stored != current;

    public static async Task<string?> LoadTopicIdAsync(string group, string topic)
    {
        await using var connection = await App.OpenAsync();
        return await connection.ExecuteScalarAsync<string?>(
            "SELECT topic_id FROM tb_kafka_projection_generations WHERE consumer_group = @group AND topic_name = @topic", new { group, topic });
    }

    /// <summary>Claims an assigned partition and atomically resets its checkpoint and marker.</summary>
    public static Task<Lease> BeginReplayAsync(string group, string topic, int partition, long beginningOffset, string owner) =>
        App.TransactionAsync(async (connection, transaction) =>
        {
            var generation = await LockGenerationAsync(connection, transaction, group, topic);
            var checkpoint = await connection.QueryAsync<(long Generation, string? Owner, long LeaseActive)>("""
                SELECT replay_generation, replay_owner,
                       COALESCE(replay_lease_expires_at > CURRENT_TIMESTAMP(6), 0)
                FROM tb_kafka_projection_offsets
                WHERE consumer_group = @group AND topic_name = @topic AND partition_id = @partition
                FOR UPDATE
                """, new { group, topic, partition }, transaction).FirstOrNullAsync();

            if (!AssignmentClaimable(checkpoint?.Generation, checkpoint?.Owner, checkpoint?.LeaseActive == 1, generation, owner))
                throw new LeaseBusy();

            var token = Guid.NewGuid().ToString();
            var parameters = new { beginningOffset, generation, token, owner, group, topic, partition };
            await connection.ExecuteAsync(checkpoint is null
                ? """
                  INSERT INTO tb_kafka_projection_offsets
                      (consumer_group, topic_name, partition_id, next_offset,
                       replay_generation, replay_token, replay_owner, replay_lease_expires_at)
                  VALUES (@group, @topic, @partition, @beginningOffset, @generation, @token, @owner,
                          DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 15 SECOND))
                  """
                : """
                  UPDATE tb_kafka_projection_offsets
                  SET next_offset = @beginningOffset,
                      snapshot_completed_offset = NULL,
                      replay_generation = @generation,
                      replay_token = @token,
                      replay_owner = @owner,
                      replay_lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 15 SECOND),
                      updated_at = CURRENT_TIMESTAMP(6)
                  WHERE consumer_group = @group AND topic_name = @topic AND partition_id = @partition
                  """, parameters, transaction);
            await connection.ExecuteAsync(
                "DELETE FROM tb_kafka_projection_barriers WHERE consumer_group = @group AND topic_name = @topic AND partition_id = @partition",
                new { group, topic, partition }, transaction);

            return new Lease(group, topic, partition, generation, token, owner, beginningOffset);
        });

    internal static bool AssignmentClaimable(long? checkpointGeneration, string? checkpointOwner, bool leaseActive, long generation, string owner) =>
        checkpointGeneration is null || checkpointGeneration < generation || checkpointOwner == owner || !leaseActive;

    /// <summary>Confirms this runtime still owns the lease claimed for an assignment.</summary>
    public static async Task<Lease> AssignmentLeaseAsync(string group, string topic, int partition, string owner)
    {
        await using var connection = await App.OpenAsync();
        var rows = (await connection.QueryAsync<(long Generation, string? Token, string? Owner, long NextOffset)>("""
            SELECT checkpoint.replay_generation, checkpoint.replay_token, checkpoint.replay_owner, checkpoint.next_offset
            FROM tb_kafka_projection_offsets AS checkpoint
            JOIN tb_kafka_projection_generations AS generations
              ON generations.consumer_group = checkpoint.consumer_group
             AND generations.topic_name = checkpoint.topic_name
             AND generations.replay_generation = checkpoint.replay_generation
            WHERE checkpoint.consumer_group = @group
              AND checkpoint.topic_name = @topic
              AND checkpoint.partition_id = @partition
              AND checkpoint.replay_lease_expires_at > CURRENT_TIMESTAMP(6)
            """, new { group, topic, partition })).ToList();

        return rows switch
        {
            [var row] when row.Owner == owner && row.Generation > 0 && row.Token is not null =>
                new Lease(group, topic, partition, row.Generation, row.Token, owner, row.NextOffset),
            [_] => throw new InvalidOperationException(":assignment_replay_superseded"),
            _ => throw new InvalidOperationException(":assignment_replay_not_initialized"),
        };
    }

    /// <summary>
    /// Locks and verifies the global generation and partition lease, then renews the lease. Runs
    /// before the projection mutation so a revoked worker rolls back.
    /// </summary>
    public static async Task LockLeaseAsync(MySqlConnection connection, MySqlTransaction transaction, Lease lease)
    {
        if (await LockGenerationAsync(connection, transaction, lease.ConsumerGroup, lease.Topic) != lease.Generation)
            throw new InvalidOperationException(":stale_replay_generation");

        var current = await connection.QueryAsync<(long Generation, string? Token, long LeaseActive)>("""
            SELECT replay_generation, replay_token, COALESCE(replay_lease_expires_at > CURRENT_TIMESTAMP(6), 0)
            FROM tb_kafka_projection_offsets
            WHERE consumer_group = @ConsumerGroup AND topic_name = @Topic AND partition_id = @Partition
            FOR UPDATE
            """, lease, transaction).FirstOrNullAsync() ?? throw new InvalidOperationException(":assignment_replay_not_initialized");
        if (!LeaseMatches(current.Generation, current.Token, current.LeaseActive == 1, lease.Generation, lease.Token))
            throw new InvalidOperationException(":stale_replay_lease");

        var renewed = await connection.ExecuteAsync("""
            UPDATE tb_kafka_projection_offsets
            SET replay_lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 15 SECOND),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_group = @ConsumerGroup AND topic_name = @Topic AND partition_id = @Partition
              AND replay_generation = @Generation AND BINARY replay_token = BINARY @Token
              AND replay_lease_expires_at > CURRENT_TIMESTAMP(6)
            """, lease, transaction);
        if (renewed != 1) throw new InvalidOperationException(":stale_replay_lease");
    }

    internal static bool LeaseMatches(long currentGeneration, string? currentToken, bool leaseActive, long generation, string token) =>
        leaseActive && currentGeneration == generation && generation > 0 && currentToken == token;

    public static async Task AdvanceAsync(MySqlConnection connection, MySqlTransaction transaction, Lease lease, long nextOffset)
    {
        await LockLeaseAsync(connection, transaction, lease);
        await connection.ExecuteAsync("""
            UPDATE tb_kafka_projection_offsets
            SET next_offset = GREATEST(next_offset, @nextOffset), updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_group = @ConsumerGroup AND topic_name = @Topic AND partition_id = @Partition
            """, new { nextOffset, lease.ConsumerGroup, lease.Topic, lease.Partition }, transaction);
    }

    public static Task RenewAssignmentAsync(Lease lease) =>
        App.TransactionAsync(async (connection, transaction) =>
        {
            await LockLeaseAsync(connection, transaction, lease);
            return 0;
        });

    public static async Task ReleaseAssignmentAsync(Lease lease)
    {
        await using var connection = await App.OpenAsync();
        await connection.ExecuteAsync("""
            UPDATE tb_kafka_projection_offsets
            SET replay_lease_expires_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_group = @ConsumerGroup AND topic_name = @Topic AND partition_id = @Partition
              AND replay_generation = @Generation AND BINARY replay_token = BINARY @Token
            """, lease);
    }

    public static Task MarkInvalidRecordAsync(MySqlConnection connection, MySqlTransaction transaction, Lease lease, long recordOffset) =>
        UpdateRecoveryStateAsync(connection, transaction, lease, state => LatchInvalidRecord(state, recordOffset));

    public static Task MarkSnapshotCompletedAsync(MySqlConnection connection, MySqlTransaction transaction, Lease lease, long markerOffset, string snapshotId) =>
        UpdateRecoveryStateAsync(connection, transaction, lease, state => ObserveRecoverySnapshot(state, markerOffset, snapshotId));

    /// <summary>
    /// A newer invalid offset starts recovery over; re-observing the same or an older invalid record
    /// during a full replay keeps recovery progress made after that gap.
    /// </summary>
    internal static RecoveryState LatchInvalidRecord(RecoveryState state, long recordOffset) =>
        state.InvalidRecordOffset >= recordOffset ? state : state with { InvalidRecordOffset = recordOffset, RecoverySnapshotId = null };

    /// <summary>
    /// The invalid-record latch clears only after two different, monotonically observed snapshot IDs
    /// after the bad offset.
    /// </summary>
    internal static RecoveryState ObserveRecoverySnapshot(RecoveryState state, long markerOffset, string snapshotId)
    {
        if (state.SnapshotCompletedOffset is { } previous && markerOffset <= previous) return state;

        var next = state with { SnapshotCompletedOffset = markerOffset, LastSnapshotId = snapshotId };
        if (state.InvalidRecordOffset is not { } invalidOffset) return next with { RecoverySnapshotId = null };
        if (markerOffset <= invalidOffset) return next;
        if (snapshotId == state.LastSnapshotId || snapshotId == state.RecoverySnapshotId) return next;
        if (state.RecoverySnapshotId is null) return next with { RecoverySnapshotId = snapshotId };
        return next with { InvalidRecordOffset = null, RecoverySnapshotId = null };
    }

    static async Task UpdateRecoveryStateAsync(MySqlConnection connection, MySqlTransaction transaction, Lease lease, Func<RecoveryState, RecoveryState> transition)
    {
        await LockLeaseAsync(connection, transaction, lease);
        var row = await connection.QueryAsync<(long? InvalidRecordOffset, long? SnapshotCompletedOffset, string? LastSnapshotId, string? RecoverySnapshotId)>("""
            SELECT invalid_record_offset, snapshot_completed_offset, last_snapshot_id, recovery_snapshot_id
            FROM tb_kafka_projection_offsets
            WHERE consumer_group = @ConsumerGroup AND topic_name = @Topic AND partition_id = @Partition
              AND replay_generation = @Generation AND BINARY replay_token = BINARY @Token
            FOR UPDATE
            """, lease, transaction).FirstOrNullAsync() ?? throw new InvalidOperationException(":stale_replay_lease");

        var next = transition(new RecoveryState(row.InvalidRecordOffset, row.SnapshotCompletedOffset, row.LastSnapshotId, row.RecoverySnapshotId));
        await connection.ExecuteAsync("""
            UPDATE tb_kafka_projection_offsets
            SET invalid_record_offset = @InvalidRecordOffset,
                snapshot_completed_offset = @SnapshotCompletedOffset,
                last_snapshot_id = @LastSnapshotId,
                recovery_snapshot_id = @RecoverySnapshotId,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_group = @ConsumerGroup AND topic_name = @Topic AND partition_id = @Partition
              AND replay_generation = @Generation AND BINARY replay_token = BINARY @Token
            """, new
        {
            next.InvalidRecordOffset, next.SnapshotCompletedOffset, next.LastSnapshotId, next.RecoverySnapshotId,
            lease.ConsumerGroup, lease.Topic, lease.Partition, lease.Generation, lease.Token,
        }, transaction);
    }

    /// <summary>Records a barrier under the current lease; a stale generation or token cannot overwrite it.</summary>
    public static Task ObserveBarrierAsync(MySqlConnection connection, MySqlTransaction transaction, Lease lease, long markerOffset, ProjectionBarrier.Marker marker) =>
        connection.ExecuteAsync("""
            INSERT INTO tb_kafka_projection_barriers
                (consumer_group, topic_name, partition_id, marker_offset,
                 replay_generation, replay_token, snapshot_id, source_service, occurred_at)
            VALUES (@ConsumerGroup, @Topic, @Partition, @markerOffset, @Generation, @Token, @SnapshotId, @Source, @OccurredAt)
            ON DUPLICATE KEY UPDATE
                snapshot_id = IF(
                    VALUES(replay_generation) = replay_generation
                    AND VALUES(replay_token) = replay_token
                    AND VALUES(marker_offset) >= marker_offset,
                    VALUES(snapshot_id),
                    snapshot_id
                ),
                source_service = IF(
                    VALUES(replay_generation) = replay_generation
                    AND VALUES(replay_token) = replay_token
                    AND VALUES(marker_offset) >= marker_offset,
                    VALUES(source_service),
                    source_service
                ),
                occurred_at = IF(
                    VALUES(replay_generation) = replay_generation
                    AND VALUES(replay_token) = replay_token
                    AND VALUES(marker_offset) >= marker_offset,
                    VALUES(occurred_at),
                    occurred_at
                ),
                marker_offset = IF(
                    VALUES(replay_generation) = replay_generation
                    AND VALUES(replay_token) = replay_token,
                    GREATEST(marker_offset, VALUES(marker_offset)),
                    marker_offset
                )
            """, new
        {
            lease.ConsumerGroup, lease.Topic, lease.Partition, markerOffset, lease.Generation, lease.Token,
            marker.SnapshotId, marker.Source, marker.OccurredAt,
        }, transaction);

    public sealed record Marker(long MarkerOffset, long Generation, string? Token, string SnapshotId, string Source);

    /// <summary>Checkpoints, markers and generation of one projection source, keyed by partition.</summary>
    public static async Task<(Dictionary<int, State> Checkpoints, Dictionary<int, Marker> Markers, long? Generation)> LoadAsync(string group, string topic)
    {
        await using var connection = await App.OpenAsync();
        var checkpoints = (await connection.QueryAsync<CheckpointRow>("""
                SELECT partition_id, next_offset, replay_generation, replay_token,
                       invalid_record_offset, snapshot_completed_offset, last_snapshot_id, recovery_snapshot_id,
                       COALESCE(replay_lease_expires_at > CURRENT_TIMESTAMP(6), 0) AS replay_lease_active
                FROM tb_kafka_projection_offsets
                WHERE consumer_group = @group AND topic_name = @topic
                """, new { group, topic })).ToList();
        var markers = (await connection.QueryAsync<(int Partition, long MarkerOffset, long Generation, string? Token, string SnapshotId, string Source)>("""
                SELECT partition_id, marker_offset, replay_generation, replay_token, snapshot_id, source_service
                FROM tb_kafka_projection_barriers
                WHERE consumer_group = @group AND topic_name = @topic
                """, new { group, topic })).ToDictionary(m => m.Partition, m => new Marker(m.MarkerOffset, m.Generation, m.Token, m.SnapshotId, m.Source));
        var generation = await connection.QuerySingleOrDefaultAsync<long?>(
            "SELECT replay_generation FROM tb_kafka_projection_generations WHERE consumer_group = @group AND topic_name = @topic",
            new { group, topic });

        return (checkpoints.ToDictionary(c => c.PartitionId, c => new State(c.NextOffset, c.ReplayGeneration, c.ReplayToken, c.InvalidRecordOffset,
            c.SnapshotCompletedOffset, c.LastSnapshotId, c.RecoverySnapshotId, c.ReplayLeaseActive == 1)), markers, generation);
    }

    sealed class CheckpointRow
    {
        public int PartitionId { get; init; }
        public long NextOffset { get; init; }
        public long ReplayGeneration { get; init; }
        public string? ReplayToken { get; init; }
        public long? InvalidRecordOffset { get; init; }
        public long? SnapshotCompletedOffset { get; init; }
        public string? LastSnapshotId { get; init; }
        public string? RecoverySnapshotId { get; init; }
        public long ReplayLeaseActive { get; init; }
    }

    static async Task<long> LockGenerationAsync(MySqlConnection connection, MySqlTransaction transaction, string group, string topic) =>
        await connection.QuerySingleOrDefaultAsync<long?>(
            "SELECT replay_generation FROM tb_kafka_projection_generations WHERE consumer_group = @group AND topic_name = @topic FOR UPDATE",
            new { group, topic }, transaction)
        ?? throw new InvalidOperationException(":replay_generation_not_started");
}
