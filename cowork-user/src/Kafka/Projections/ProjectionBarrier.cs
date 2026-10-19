using System.Text.Json;
using CoworkUser.Platform;

namespace CoworkUser.Kafka.Projections;

public static class ProjectionBarrier
{
    public const string EventType = "PROJECTION_SNAPSHOT_COMPLETED";
    const string KeyPrefix = "__cowork_projection_snapshot_complete__";

    public sealed record Marker(string SnapshotId, string Source, DateTime OccurredAt);

    public static string Key(int partition) => $"{KeyPrefix}:{partition}";

    public static object Event(string topic, int partition, string snapshotId, DateTime occurredAt, string source) => new
    {
        eventType = EventType,
        topic,
        partition,
        snapshotId,
        occurredAt = Iso8601.Format(occurredAt),
        source,
    };

    /// <summary>Null when the payload is not a barrier; throws for a malformed barrier.</summary>
    public static Marker? Parse(JsonElement payload, string? recordKey, string expectedTopic, int expectedPartition, string expectedSource)
    {
        if (payload.GetString("eventType") != EventType) return null;
        if (recordKey != Key(expectedPartition)) throw new InvalidRecord(":invalid_barrier_key");
        if (payload.GetString("topic") != expectedTopic) throw new InvalidRecord(":invalid_barrier_topic");
        if (payload.Get("partition").Integer() != expectedPartition) throw new InvalidRecord(":invalid_barrier_partition");
        if (payload.GetString("snapshotId") is not { } snapshotId || !Guid.TryParseExact(snapshotId, "D", out _)
            || payload.GetString("source") is not { Length: > 0 } source)
            throw new InvalidRecord(":invalid_projection_snapshot_barrier");
        if (source != expectedSource) throw new InvalidRecord(":invalid_barrier_source");
        if (!Iso8601.TryParse(payload.GetString("occurredAt"), out var occurredAt, out _))
            throw new InvalidRecord(":invalid_projection_snapshot_barrier");
        return new Marker(snapshotId, source, occurredAt);
    }
}
