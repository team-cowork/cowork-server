using System.Text.Json;
using Confluent.Kafka;
using CoworkUser.Config;
using CoworkUser.Kafka.Commands;
using CoworkUser.Kafka.Projections;
using CoworkUser.Platform;
using MySqlConnector;

namespace CoworkUser.Kafka;

/// <summary>
/// Runs the owner command consumers (Kafka-committed offsets) and the projection consumers
/// (database checkpoints with replay fencing). Any consumer failure restarts all of them with
/// backoff, closing projection readiness until they reconnect.
/// </summary>
public sealed class Consumers(AppConfig config, ProjectionReadiness readiness, KafkaInspector kafka, ILogger<Consumers> logger) : BackgroundService
{
    const int InitialBackoffMs = 5_000;
    const int MaxBackoffMs = 60_000;
    const int LeaseHeartbeatMs = 5_000;
    const int ClaimRetryMs = 250;
    const int ClaimTimeoutMs = 10_000;

    delegate Task ProjectionApply(MySqlConnection connection, MySqlTransaction transaction, KafkaRecord record, JsonElement payload);

    TaskCompletionSource forcedReplay = new(TaskCreationOptions.RunContinuationsAsynchronously);

    /// <summary>Restarts every consumer under a new replay generation.</summary>
    public void ForceReplay(string reason)
    {
        logger.LogWarning("Restarting Kafka subscribers for a fenced full replay: {Reason}", reason);
        forcedReplay.TrySetResult();
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var backoff = InitialBackoffMs;
        var generationStarted = false;
        while (!stoppingToken.IsCancellationRequested)
        {
            var delay = backoff;
            try
            {
                if (!generationStarted)
                {
                    // Bumped before joining the groups: every older partition worker is fenced at once.
                    var rebuilt = await ProjectionCheckpoint.StartReplayGenerationAsync([
                        new(config.KafkaTeamMemberGroupId, config.KafkaTeamMemberTopic, kafka.TopicId(config.KafkaTeamMemberTopic),
                            TeamMembershipProjection.ResetAsync),
                        new(config.KafkaPresenceGroupId, config.KafkaPresenceTopic, kafka.TopicId(config.KafkaPresenceTopic),
                            PresenceProjection.ResetAsync),
                    ]);
                    foreach (var topic in rebuilt)
                        logger.LogWarning("Rebuilt projection and checkpoints because the topic identity could not be matched topic={Topic}", topic);
                    generationStarted = true;
                }

                forcedReplay = new(TaskCreationOptions.RunContinuationsAsynchronously);
                using var running = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
                var workers = new[]
                {
                    Run(() => RunActionConsumer(config.KafkaTopic, config.KafkaGroupId,
                        record => SyncHandler.HandleAsync(config.KafkaGroupId, record, logger), running.Token)),
                    Run(() => RunActionConsumer(config.KafkaIdentityCommandTopic, config.KafkaIdentityCommandGroupId,
                        record => IdentityCommand.HandleAsync(config.KafkaIdentityCommandGroupId, config.KafkaIdentityCommandResultTopic, record, logger),
                        running.Token)),
                    Run(() => RunProjectionConsumer(config.KafkaTeamMemberTopic, config.KafkaTeamMemberGroupId, "cowork-team",
                        "team.member.event", ApplyTeamMember, running.Token)),
                    Run(() => RunProjectionConsumer(config.KafkaPresenceTopic, config.KafkaPresenceGroupId, "cowork-authorization",
                        "user.presence.event", ApplyPresence, running.Token)),
                };
                logger.LogInformation("Kafka consumers connected for user owner commands and state projections.");
                readiness.ConsumerConnected();
                backoff = InitialBackoffMs;

                var finished = await Task.WhenAny(workers.Append(forcedReplay.Task).Append(Task.Delay(Timeout.Infinite, stoppingToken)));
                readiness.ConsumerDisconnected();
                running.Cancel();
                await Task.WhenAll(workers.Select(w => w.ContinueWith(_ => { }, TaskScheduler.Default)));

                if (finished == forcedReplay.Task)
                {
                    generationStarted = false;
                    delay = 0;
                }
                else if (!stoppingToken.IsCancellationRequested)
                {
                    logger.LogWarning("Kafka subscriber stopped: {Reason}", finished.Exception?.GetBaseException().Message ?? "completed");
                    backoff = Math.Min(backoff * 2, MaxBackoffMs);
                }
            }
            catch (Exception exception) when (!stoppingToken.IsCancellationRequested)
            {
                readiness.ConsumerDisconnected();
                logger.LogWarning("Kafka consumer connection failed: {Reason}", exception.Message);
                backoff = Math.Min(backoff * 2, MaxBackoffMs);
            }

            try
            {
                await Task.Delay(delay, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    // Consume blocks its thread; each consumer gets a dedicated one.
    static Task Run(Action consumer) => Task.Factory.StartNew(consumer, TaskCreationOptions.LongRunning);

    static KafkaRecord Record(ConsumeResult<byte[], byte[]> result) =>
        new(result.Topic, result.Partition.Value, result.Offset.Value, result.Message.Key, result.Message.Value);

    void RunActionConsumer(string topic, string groupId, Func<KafkaRecord, Task> handle, CancellationToken cancellationToken)
    {
        using var consumer = new ConsumerBuilder<byte[], byte[]>(new ConsumerConfig
        {
            BootstrapServers = config.KafkaBootstrapServers,
            GroupId = groupId,
            AutoOffsetReset = AutoOffsetReset.Earliest,
            EnableAutoCommit = true,
            // Only handled records are stored for commit; a failure replays from the last success.
            EnableAutoOffsetStore = false,
        }).Build();
        consumer.Subscribe(topic);
        try
        {
            while (true)
            {
                var result = consumer.Consume(cancellationToken);
                handle(Record(result)).GetAwaiter().GetResult();
                consumer.StoreOffset(result);
            }
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            consumer.Close();
        }
    }

    void RunProjectionConsumer(string topic, string groupId, string barrierSource, string eventName, ProjectionApply apply,
        CancellationToken cancellationToken)
    {
        var owner = Guid.NewGuid().ToString();
        var leases = new Dictionary<int, ProjectionCheckpoint.Lease>();
        var assignmentEpoch = 0;

        void Release(IEnumerable<int> partitions)
        {
            assignmentEpoch++;
            foreach (var partition in partitions.ToList())
            {
                if (!leases.Remove(partition, out var lease)) continue;
                try
                {
                    ProjectionCheckpoint.ReleaseAssignmentAsync(lease).GetAwaiter().GetResult();
                }
                catch (Exception exception)
                {
                    logger.LogWarning("Failed to release projection lease: {Reason}", exception.Message);
                }
            }
        }

        using var consumer = new ConsumerBuilder<byte[], byte[]>(new ConsumerConfig
            {
                BootstrapServers = config.KafkaBootstrapServers,
                GroupId = groupId,
                AutoOffsetReset = AutoOffsetReset.Earliest,
                EnableAutoCommit = false,
            })
            .SetPartitionsAssignedHandler((c, partitions) =>
            {
                // Every assignment replays the retained log from its earliest offset under a new lease.
                assignmentEpoch++;
                var offsets = partitions.Select(tp => new TopicPartitionOffset(tp, BeginAssignment(c, groupId, tp, owner))).ToList();
                foreach (var tp in partitions)
                {
                    leases[tp.Partition.Value] = ProjectionCheckpoint.AssignmentLeaseAsync(groupId, topic, tp.Partition.Value, owner).GetAwaiter().GetResult();
                    readiness.AssignmentReplayFinished(groupId, topic, tp.Partition.Value);
                }
                return offsets;
            })
            .SetPartitionsRevokedHandler((_, partitions) => Release(partitions.Select(p => p.Partition.Value)))
            .SetPartitionsLostHandler((_, partitions) => Release(partitions.Select(p => p.Partition.Value)))
            .Build();

        consumer.Subscribe(topic);
        var nextRenewal = Environment.TickCount64 + LeaseHeartbeatMs;
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                if (Environment.TickCount64 >= nextRenewal)
                {
                    foreach (var lease in leases.Values) ProjectionCheckpoint.RenewAssignmentAsync(lease).GetAwaiter().GetResult();
                    nextRenewal = Environment.TickCount64 + LeaseHeartbeatMs;
                }

                var first = consumer.Consume(TimeSpan.FromSeconds(1));
                if (first is null) continue;
                var epoch = assignmentEpoch;
                var batch = new List<KafkaRecord> { Record(first) };
                while (batch.Count < ProjectionProcessor.TransactionBatchSize && consumer.Consume(TimeSpan.Zero) is { } next)
                    batch.Add(Record(next));
                // Records fetched before a rebalance must not run under the next assignment's lease;
                // a restart replays every assignment from its earliest offset.
                if (assignmentEpoch != epoch) throw new TransientSyncError($"{eventName} assignment changed during a batch");

                try
                {
                    // Projection changes and checkpoints commit together; a batch bounds row-lock lifetime.
                    App.TransactionAsync(async (connection, transaction) =>
                    {
                        foreach (var record in batch)
                        {
                            var lease = leases[record.Partition];
                            await ProjectionProcessor.ProcessAsync(connection, transaction, lease, record,
                                () => ApplyRecordAsync(connection, transaction, lease, record, barrierSource, apply),
                                readiness.StateGapDetected, logger, eventName);
                        }
                        return 0;
                    }).GetAwaiter().GetResult();
                }
                catch (Exception exception)
                {
                    throw new TransientSyncError($"{eventName} projection batch failed: {exception.Message}", exception);
                }
            }
        }
        finally
        {
            Release(leases.Keys);
            consumer.Close();
        }
    }

    static async Task ApplyRecordAsync(MySqlConnection connection, MySqlTransaction transaction, ProjectionCheckpoint.Lease lease,
        KafkaRecord record, string barrierSource, ProjectionApply apply)
    {
        var payload = Json.ParseObject(record.Value, out var error) ?? throw new InvalidRecord(error!);
        if (ProjectionBarrier.Parse(payload, record.KeyText, record.Topic, record.Partition, barrierSource) is { } marker)
        {
            await ProjectionCheckpoint.ObserveBarrierAsync(connection, transaction, lease, record.Offset, marker);
            await ProjectionCheckpoint.MarkSnapshotCompletedAsync(connection, transaction, lease, record.Offset, marker.SnapshotId);
            return;
        }
        await apply(connection, transaction, record, payload);
    }

    static Task ApplyTeamMember(MySqlConnection connection, MySqlTransaction transaction, KafkaRecord record, JsonElement payload)
    {
        var expectedKey = $"{EventParser.PositiveInteger(payload, "teamId", "team_id")}:{EventParser.PositiveInteger(payload, "userId", "user_id")}";
        if (record.KeyText != expectedKey) throw new InvalidRecord($"{{:mismatched_key, \"{record.KeyText}\", \"{expectedKey}\"}}");
        return TeamMembershipProjection.ApplyAsync(connection, transaction, TeamMembershipProjection.Parse(payload));
    }

    static Task ApplyPresence(MySqlConnection connection, MySqlTransaction transaction, KafkaRecord record, JsonElement payload)
    {
        var userId = EventParser.PositiveInteger(payload, "userId", "user_id");
        if (record.KeyText != userId.ToString()) throw new InvalidRecord($"{{:mismatched_key, \"{record.KeyText}\", {userId}}}");
        return PresenceProjection.ApplyAsync(connection, transaction, PresenceProjection.Parse(payload));
    }

    long BeginAssignment(IConsumer<byte[], byte[]> consumer, string groupId, TopicPartition partition, string owner)
    {
        readiness.AssignmentReplayStarted(groupId, partition.Topic, partition.Partition.Value);
        var beginning = consumer.QueryWatermarkOffsets(partition, TimeSpan.FromSeconds(10)).Low.Value;
        var deadline = Environment.TickCount64 + ClaimTimeoutMs;
        while (true)
        {
            try
            {
                return ProjectionCheckpoint.BeginReplayAsync(groupId, partition.Topic, partition.Partition.Value, beginning, owner)
                    .GetAwaiter().GetResult().BeginningOffset;
            }
            catch (ProjectionCheckpoint.LeaseBusy) when (Environment.TickCount64 < deadline)
            {
                Thread.Sleep(ClaimRetryMs);
            }
            // First claims of sibling groups insert neighbouring checkpoint rows and can deadlock on gap locks.
            catch (MySqlException error) when (error.ErrorCode == MySqlErrorCode.LockDeadlock && Environment.TickCount64 < deadline)
            {
                Thread.Sleep(ClaimRetryMs);
            }
        }
    }
}
