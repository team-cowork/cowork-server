using System.Globalization;
using System.Text;
using System.Text.Json;

namespace CoworkUser.Kafka;

/// <summary>A record rejected by its contract; the reason is stored with the quarantined record.</summary>
public sealed class InvalidRecord(string reason) : Exception(reason);

public static class EventParser
{
    public static long PositiveInteger(JsonElement payload, string camelKey, string snakeKey)
    {
        var value = payload.GetEither(camelKey, snakeKey);
        if (value.Integer() is > 0 and var number) return number;
        if (value is { ValueKind: JsonValueKind.String } text
            && long.TryParse(text.GetString(), NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var parsed) && parsed > 0)
            return parsed;
        throw new InvalidRecord($"{{:invalid_positive_integer, \"{camelKey}\"}}");
    }

    /// <summary>An offset date-time converted to UTC with microsecond precision.</summary>
    public static DateTime UtcDateTime(JsonElement payload, string camelKey, string snakeKey) =>
        payload.GetEither(camelKey, snakeKey) is { ValueKind: JsonValueKind.String } text && Iso8601.TryParse(text.GetString(), out var parsed, out _)
            ? parsed
            : throw new InvalidRecord($"{{:invalid_datetime, \"{camelKey}\"}}");
}

static class ContractFields
{
    public static bool ExactKeys(JsonElement payload, IReadOnlySet<string> keys) =>
        payload.EnumerateObject().Select(p => p.Name).ToHashSet().SetEquals(keys);

    public static bool RequiredString(JsonElement? value, int maxLength) =>
        value is { ValueKind: JsonValueKind.String } text && text.GetString()!.Trim() != ""
        && Accounts.TextLength(text.GetString()!) <= maxLength;

    public static bool OptionalString(JsonElement? value, int maxLength) =>
        value is null or { ValueKind: JsonValueKind.Null }
        || (value is { ValueKind: JsonValueKind.String } text && Accounts.TextLength(text.GetString()!) <= maxLength);

    public static bool OptionalInteger(JsonElement? value) =>
        value is null or { ValueKind: JsonValueKind.Null } || value.Integer() is not null;

    public static string? String(JsonElement? value) =>
        value is { ValueKind: JsonValueKind.String } text ? text.GetString() : null;
}

public static class UserSyncContract
{
    static readonly HashSet<string> AllowedKeys =
    [
        "event_type", "event_id", "event_index", "occurred_at", "email", "name", "sex", "student_role",
        "student_number", "major", "specialty", "github_id", "datagsm_student_id",
    ];

    public static bool Valid(JsonElement payload, string? key) =>
        ContractFields.ExactKeys(payload, AllowedKeys)
        && payload.GetString("event_type") == "student.updated"
        && ContractFields.RequiredString(payload.Get("event_id"), 255)
        && payload.Get("event_index").Integer() is >= 0
        && Iso8601.TryParse(payload.GetString("occurred_at"), out _, out _)
        && ContractFields.RequiredString(payload.Get("email"), 255)
        && ContractFields.RequiredString(payload.Get("name"), 50)
        && ContractFields.RequiredString(payload.Get("sex"), 10)
        && ContractFields.RequiredString(payload.Get("student_role"), 50)
        && ContractFields.OptionalInteger(payload.Get("student_number"))
        && ContractFields.OptionalString(payload.Get("major"), 50)
        && ContractFields.OptionalString(payload.Get("specialty"), 255)
        && ContractFields.OptionalString(payload.Get("github_id"), 100)
        && payload.Get("datagsm_student_id").Integer() is > 0 and var studentId
        && key == studentId.ToString(CultureInfo.InvariantCulture);
}

public static class IdentityCommandContract
{
    const int MaxIdempotencyKeyLength = 128;

    static readonly HashSet<string> AllowedKeys =
    [
        "schemaVersion", "operationId", "idempotencyKey", "commandType", "userId", "name", "email", "sex", "grade",
        "classNumber", "studentNumberInClass", "major", "role", "githubId", "dataGSMStudentId", "requestedBy", "occurredAt",
    ];

    public sealed record Command(
        string OperationId, string IdempotencyKey, long UserId, string Name, string Email, string Sex,
        long? Grade, long? ClassNumber, long? StudentNumberInClass, string Major, string Role, string? GithubId,
        long? DatagsmStudentId, long RequestedBy, DateTime OccurredAt, int OccurredAtPrecision);

    public static Command? Parse(JsonElement payload, string? key)
    {
        var operationId = Uuid(payload.Get("operationId"));
        var idempotencyKey = ContractFields.String(payload.Get("idempotencyKey"));
        var userId = payload.Get("userId").Integer();
        if (!ContractFields.ExactKeys(payload, AllowedKeys)
            || payload.Get("schemaVersion").Integer() != 1
            || operationId is null
            || idempotencyKey is null || idempotencyKey.Trim() == "" || Accounts.TextLength(idempotencyKey) > MaxIdempotencyKeyLength
            || payload.GetString("commandType") != "UPSERT"
            || userId is not > 0
            || key != userId.Value.ToString(CultureInfo.InvariantCulture)
            || payload.Get("requestedBy").Integer() != userId
            || !ContractFields.RequiredString(payload.Get("name"), 50)
            || !ContractFields.RequiredString(payload.Get("email"), 255)
            || !ContractFields.RequiredString(payload.Get("sex"), 10)
            || !ContractFields.RequiredString(payload.Get("major"), 50)
            || !ContractFields.RequiredString(payload.Get("role"), 50)
            || !ContractFields.OptionalInteger(payload.Get("grade"))
            || !ContractFields.OptionalInteger(payload.Get("classNumber"))
            || !ContractFields.OptionalInteger(payload.Get("studentNumberInClass"))
            || !ContractFields.OptionalString(payload.Get("githubId"), 100)
            || !(payload.Get("dataGSMStudentId") is null or { ValueKind: JsonValueKind.Null } || payload.Get("dataGSMStudentId").Integer() is > 0)
            || !Iso8601.TryParse(payload.GetString("occurredAt"), out var occurredAt, out var precision))
            return null;

        return new Command(operationId, idempotencyKey, userId.Value,
            payload.GetString("name")!, payload.GetString("email")!, payload.GetString("sex")!,
            payload.Get("grade").Integer(), payload.Get("classNumber").Integer(), payload.Get("studentNumberInClass").Integer(),
            payload.GetString("major")!, payload.GetString("role")!, payload.GetString("githubId"),
            payload.Get("dataGSMStudentId").Integer(), userId.Value, occurredAt, precision);
    }

    /// <summary>A canonical lowercase UUID operationId usable for result correlation.</summary>
    public static string? CorrelationOperationId(JsonElement payload) => Uuid(payload.Get("operationId"));

    static string? Uuid(JsonElement? value) =>
        ContractFields.String(value) is { } text && Guid.TryParseExact(text, "D", out var guid) && guid.ToString("D") == text ? text : null;

    /// <summary>Version of the hash rule below; inbox rows record it so a rule change never invalidates stored hashes.</summary>
    public const int HashVersion = 2;

    /// <summary>
    /// The command as hashed since <see cref="HashVersion"/> 2: compact JSON with keys in byte order and the
    /// timestamp normalized to microseconds, so equal commands hash equally whatever their textual form.
    /// </summary>
    public static string CanonicalPayload(Command command) => JsonSerializer.Serialize(new SortedDictionary<string, object?>(StringComparer.Ordinal)
    {
        ["schemaVersion"] = 1,
        ["operationId"] = command.OperationId,
        ["idempotencyKey"] = command.IdempotencyKey,
        ["commandType"] = "UPSERT",
        ["userId"] = command.UserId,
        ["name"] = command.Name,
        ["email"] = command.Email,
        ["sex"] = command.Sex,
        ["grade"] = command.Grade,
        ["classNumber"] = command.ClassNumber,
        ["studentNumberInClass"] = command.StudentNumberInClass,
        ["major"] = command.Major,
        ["role"] = command.Role,
        ["githubId"] = command.GithubId,
        ["dataGSMStudentId"] = command.DatagsmStudentId,
        ["requestedBy"] = command.RequestedBy,
        ["occurredAt"] = Iso8601.Format(command.OccurredAt),
    }, Json.Event);

    /// <summary>
    /// The command as hash version 1 (the previous runtime) hashed it: compact JSON with keys in byte order and
    /// the timestamp as written. Only rows stored under version 1 are verified with it; drop it once none remain.
    /// </summary>
    public static string LegacyCanonicalPayload(Command command) => JasonEncode(new SortedDictionary<string, object?>(StringComparer.Ordinal)
    {
        ["schemaVersion"] = 1,
        ["operationId"] = command.OperationId,
        ["idempotencyKey"] = command.IdempotencyKey,
        ["commandType"] = "UPSERT",
        ["userId"] = command.UserId,
        ["name"] = command.Name,
        ["email"] = command.Email,
        ["sex"] = command.Sex,
        ["grade"] = command.Grade,
        ["classNumber"] = command.ClassNumber,
        ["studentNumberInClass"] = command.StudentNumberInClass,
        ["major"] = command.Major,
        ["role"] = command.Role,
        ["githubId"] = command.GithubId,
        ["dataGSMStudentId"] = command.DatagsmStudentId,
        ["requestedBy"] = command.RequestedBy,
        ["occurredAt"] = Iso8601.Format(command.OccurredAt, command.OccurredAtPrecision),
    });

    // Jason escapes only quotes, backslashes and control characters; System.Text.Json also escapes
    // non-BMP and other characters, which would change the hash.
    static string JasonEncode(SortedDictionary<string, object?> fields)
    {
        var json = new StringBuilder("{");
        foreach (var (key, value) in fields)
        {
            if (json.Length > 1) json.Append(',');
            JasonString(json, key);
            json.Append(':');
            switch (value)
            {
                case null: json.Append("null"); break;
                case string text: JasonString(json, text); break;
                default: json.Append(Convert.ToString(value, CultureInfo.InvariantCulture)); break;
            }
        }
        return json.Append('}').ToString();
    }

    static void JasonString(StringBuilder json, string text)
    {
        json.Append('"');
        foreach (var c in text)
        {
            json.Append(c switch
            {
                '"' => "\\\"",
                '\\' => "\\\\",
                '\b' => "\\b",
                '\t' => "\\t",
                '\n' => "\\n",
                '\f' => "\\f",
                '\r' => "\\r",
                < ' ' => $"\\u{(int)c:X4}",
                _ => c.ToString(),
            });
        }
        json.Append('"');
    }
}

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
