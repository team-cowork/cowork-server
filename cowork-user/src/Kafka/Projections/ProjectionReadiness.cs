using Confluent.Kafka;
using CoworkUser.Config;
using CoworkUser.Platform;

namespace CoworkUser.Kafka.Projections;

/// <summary>
/// Decides whether the presence and team projections are current: every partition must have replayed
/// past the startup high-watermark and past a validated snapshot-completion marker from its source.
/// HTTP reads that depend on projections, service readiness and profile snapshots follow it.
/// </summary>
public sealed class ProjectionReadiness(AppConfig config, KafkaInspector kafka, ILogger<ProjectionReadiness> logger) : BackgroundService
{
    const int RetryMs = 2_000;
    const int RefreshIntervalMs = 500;

    public sealed record Range(long BeginningOffset, long EndOffset, long StartupEndOffset, long Generation, ValidatedMarker? Marker);

    public sealed record ValidatedMarker(long MarkerOffset, long Generation, string? Token, string SnapshotId);

    sealed class InvalidState(string reason) : Exception(reason);

    sealed class ReplayRequired(string reason) : Exception(reason);

    readonly Lock gate = new();
    volatile bool ready;
    volatile bool teamReady;
    Dictionary<(string Group, string Topic, int Partition), Range> barriers = [];
    readonly HashSet<(string, string, int)> assignmentsReplaying = [];
    bool consumerConnected;
    bool initialized;
    bool invalidLatched;
    long version;
    long initializeAfter = long.MaxValue;

    /// <summary>Invoked when readiness opens, so downstream projections get a fresh snapshot.</summary>
    public Action? BecameReady { get; set; }

    /// <summary>Invoked when the retained log no longer matches the checkpoints.</summary>
    public Action<string>? ReplayRequested { get; set; }

    public bool Ready => ready;
    public bool TeamReady => teamReady;

    (string, string)[] Sources =>
    [
        (config.KafkaTeamMemberGroupId, config.KafkaTeamMemberTopic),
        (config.KafkaPresenceGroupId, config.KafkaPresenceTopic),
    ];

    public void ConsumerConnected()
    {
        lock (gate)
        {
            if (consumerConnected) return;
            consumerConnected = true;
            if (invalidLatched) return;
            Close();
            initialized = false;
            initializeAfter = 0;
        }
    }

    public void ConsumerDisconnected()
    {
        lock (gate)
        {
            Close();
            consumerConnected = false;
            initialized = false;
            assignmentsReplaying.Clear();
            initializeAfter = long.MaxValue;
        }
    }

    public void AssignmentReplayStarted(string group, string topic, int partition)
    {
        lock (gate)
        {
            Close();
            assignmentsReplaying.Add((group, topic, partition));
        }
    }

    public void AssignmentReplayFinished(string group, string topic, int partition)
    {
        lock (gate)
        {
            assignmentsReplaying.Remove((group, topic, partition));
            if (assignmentsReplaying.Count > 0) return;
            Close();
            initialized = false;
            initializeAfter = 0;
        }
    }

    public void StateGapDetected()
    {
        lock (gate) Close();
    }

    /// <summary>Recomputes readiness now; used to bracket a profile snapshot.</summary>
    public bool Current()
    {
        lock (gate)
        {
            if (!consumerConnected || !initialized) return false;
        }
        return Refresh(capture: false);
    }

    // Callers hold the gate. Any state change invalidates readiness computed before it.
    void Close()
    {
        version++;
        ready = teamReady = false;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            bool capture, refresh;
            lock (gate)
            {
                capture = consumerConnected && !initialized && !invalidLatched && Environment.TickCount64 >= initializeAfter;
                refresh = consumerConnected && initialized;
                if (!consumerConnected || invalidLatched) ready = teamReady = false;
            }

            try
            {
                if (capture || refresh) await Task.Run(() => Refresh(capture), stoppingToken);
                await Task.Delay(RefreshIntervalMs, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    bool Refresh(bool capture)
    {
        long observed;
        Dictionary<(string, string, int), Range> previous;
        lock (gate)
        {
            observed = version;
            previous = barriers;
        }

        Dictionary<(string Group, string Topic, int Partition), Range> current;
        try
        {
            current = BuildBarriers(capture ? [] : previous.Keys.ToList());
        }
        catch (InvalidState reason)
        {
            logger.LogError("Kafka projection replay state is invalid: {Reason}", reason.Message);
            lock (gate)
            {
                Close();
                initialized = false;
                invalidLatched = true;
            }
            return false;
        }
        catch (ReplayRequired reason)
        {
            logger.LogWarning("Kafka projection source identity changed: {Reason}", reason.Message);
            lock (gate)
            {
                Close();
                initialized = false;
                initializeAfter = long.MaxValue;
            }
            ReplayRequested?.Invoke(reason.Message);
            return false;
        }
        catch (Exception exception)
        {
            logger.LogWarning("Kafka projection barrier validation failed: {Reason}", exception.Message);
            lock (gate)
            {
                Close();
                initialized = false;
                initializeAfter = Environment.TickCount64 + RetryMs;
            }
            return false;
        }

        // A rebuilt range carries the live high-watermark; keep the startup one so readiness only
        // accepts a replay that reached its starting target.
        if (!capture)
            current = current.ToDictionary(e => e.Key, e => previous.TryGetValue(e.Key, out var old) ? e.Value with { StartupEndOffset = old.StartupEndOffset } : e.Value);

        var (allReady, teamKeysReady) = ComputeReadiness(current);
        bool opened;
        lock (gate)
        {
            if (version != observed || !consumerConnected || assignmentsReplaying.Count > 0 || invalidLatched) return false;
            if (capture) logger.LogInformation("Kafka projection runtime barriers initialized.");
            barriers = current;
            initialized = true;
            opened = !ready && allReady;
            ready = allReady;
            teamReady = teamKeysReady;
        }

        if (opened) BecameReady?.Invoke();
        return allReady;
    }

    Dictionary<(string Group, string Topic, int Partition), Range> BuildBarriers(IReadOnlyList<(string Group, string Topic, int Partition)> expectedKeys)
    {
        var result = new Dictionary<(string, string, int), Range>();
        foreach (var (group, topic) in Sources)
        {
            var partitionCount = kafka.PartitionCount(topic);
            if (partitionCount <= 0) throw new InvalidState($"{topic}: invalid partition count {partitionCount}");
            if (ProjectionCheckpoint.TopicReplaced(ProjectionCheckpoint.LoadTopicIdAsync(group, topic).GetAwaiter().GetResult(), kafka.TopicId(topic)))
                throw new ReplayRequired($"topic {topic} was replaced");
            var (checkpoints, markers, generation) = ProjectionCheckpoint.LoadAsync(group, topic).GetAwaiter().GetResult();
            if (generation is not > 0) throw new InvalidOperationException(":replay_generation_not_started");

            for (var partition = 0; partition < partitionCount; partition++)
            {
                var (beginning, end) = kafka.Watermarks(topic, partition);
                var validated = ValidatePartitionReplay(topic, partition, generation.Value, beginning, end,
                    checkpoints.GetValueOrDefault(partition), markers.GetValueOrDefault(partition));
                result[(group, topic, partition)] = new Range(beginning, end, end, generation.Value, validated);
            }
        }

        if (expectedKeys.Count > 0 && !result.Keys.Order().SequenceEqual(expectedKeys.Order()))
            throw new InvalidState("partition topology changed");
        return result;
    }

    ValidatedMarker? ValidatePartitionReplay(string topic, int partition, long generation, long beginning, long end,
        ProjectionCheckpoint.State? checkpoint, ProjectionCheckpoint.Marker? marker)
    {
        if (checkpoint is null || checkpoint.Generation != generation) return null;
        if (checkpoint.Token is null) throw new ReplayRequired(":missing_replay_token");
        if (!CheckpointReplayable(checkpoint.NextOffset, beginning, end))
            throw new ReplayRequired($"projection checkpoint {checkpoint.NextOffset} outside retained log {beginning}..{end} ({topic}/{partition})");
        if (marker is null || marker.Generation != generation || marker.Token != checkpoint.Token) return null;
        if (!MarkerReplayable(marker.MarkerOffset, beginning, end))
            throw new ReplayRequired($"projection marker {marker.MarkerOffset} outside retained log {beginning}..{end} ({topic}/{partition})");

        ConsumeResult<string, byte[]>? record;
        try
        {
            record = kafka.FetchAt(topic, partition, marker.MarkerOffset);
        }
        catch (KafkaInspector.OffsetMissing)
        {
            throw new ReplayRequired($"projection marker offset {marker.MarkerOffset} missing ({topic}/{partition})");
        }

        if (!MarkerIdentityMatches(record, topic, partition, marker))
            throw new ReplayRequired($"projection marker identity mismatch at {marker.MarkerOffset} snapshot {marker.SnapshotId} ({topic}/{partition})");
        return new ValidatedMarker(marker.MarkerOffset, marker.Generation, marker.Token, marker.SnapshotId);
    }

    static bool MarkerIdentityMatches(ConsumeResult<string, byte[]>? record, string topic, int partition, ProjectionCheckpoint.Marker marker)
    {
        if (record is null || record.Offset.Value != marker.MarkerOffset) return false;
        var payload = Json.ParseObject(record.Message.Value, out _);
        if (payload is null) return false;
        try
        {
            var parsed = ProjectionBarrier.Parse(payload.Value, record.Message.Key, topic, partition, marker.Source);
            return parsed is not null && parsed.SnapshotId == marker.SnapshotId && parsed.Source == marker.Source;
        }
        catch (InvalidRecord)
        {
            return false;
        }
    }

    (bool All, bool Team) ComputeReadiness(Dictionary<(string Group, string Topic, int Partition), Range> ranges)
    {
        try
        {
            var states = Sources.ToDictionary(s => s, s => ProjectionCheckpoint.LoadAsync(s.Item1, s.Item2).GetAwaiter().GetResult());
            bool KeysReady(IEnumerable<(string Group, string Topic, int Partition)> keys)
            {
                var list = keys.ToList();
                return list.Count > 0 && list.All(key =>
                {
                    var (checkpoints, markers, generation) = states[(key.Group, key.Topic)];
                    var range = ranges[key];
                    return generation == range.Generation
                           && PartitionReady(range, checkpoints.GetValueOrDefault(key.Partition), markers.GetValueOrDefault(key.Partition));
                });
            }

            return (KeysReady(ranges.Keys), KeysReady(ranges.Keys.Where(k => k.Topic == config.KafkaTeamMemberTopic)));
        }
        catch (Exception exception)
        {
            logger.LogWarning("Kafka projection readiness check failed: {Reason}", exception.Message);
            return (false, false);
        }
    }

    /// <summary>Records a checkpoint may trail the live high-watermark before the partition stops being ready.</summary>
    internal const long MaxLagRecords = 1_000;

    /// <summary>
    /// Replay re-stamps whatever marker it meets first with the current generation, so the marker
    /// alone cannot prove catch-up; the startup high-watermark does. Holding that target fixed keeps
    /// readiness from dropping on every record the source publishes later, while the lag bound keeps
    /// a stalled consumer from staying ready.
    /// </summary>
    internal static bool PartitionReady(Range range, ProjectionCheckpoint.State? checkpoint, ProjectionCheckpoint.Marker? marker) =>
        range.Marker is { } validated
        && checkpoint is { Token: { } token, LeaseActive: true, InvalidRecordOffset: null }
        && marker is not null
        && checkpoint.Generation == range.Generation && marker.Generation == range.Generation && marker.Token == token
        && checkpoint.SnapshotCompletedOffset == marker.MarkerOffset && checkpoint.LastSnapshotId == marker.SnapshotId
        && marker.MarkerOffset >= range.BeginningOffset && marker.MarkerOffset < range.EndOffset
        && checkpoint.NextOffset > marker.MarkerOffset && checkpoint.NextOffset >= range.StartupEndOffset
        && checkpoint.NextOffset <= range.EndOffset && range.EndOffset - checkpoint.NextOffset <= MaxLagRecords
        && validated.MarkerOffset == marker.MarkerOffset && validated.SnapshotId == marker.SnapshotId
        && validated.Generation == range.Generation && validated.Token == token;

    internal static bool CheckpointReplayable(long checkpoint, long beginning, long end) => checkpoint >= beginning && checkpoint <= end;

    internal static bool MarkerReplayable(long marker, long beginning, long end) => marker >= beginning && marker < end;
}
