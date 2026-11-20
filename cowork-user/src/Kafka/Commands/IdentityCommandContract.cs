using System.Globalization;
using System.Text.Json;
using CoworkUser.Platform;
using CoworkUser.Users;

namespace CoworkUser.Kafka.Commands;

public static class IdentityCommandContract
{
    const int MaxIdempotencyKeyLength = 128;

    static readonly HashSet<string> AllowedKeys =
    [
        "schemaVersion", "operationId", "idempotencyKey", "commandType", "userId", "name", "email", "sex", "grade",
        "classNumber", "studentNumberInClass", "major", "role", "githubId", "dataGSMStudentId", "requestedBy", "occurredAt",
    ];

    public sealed record Command(
        string OperationId, string IdempotencyKey, long? UserId, string Name, string Email, string Sex,
        long? Grade, long? ClassNumber, long? StudentNumberInClass, string? Major, string Role, string? GithubId,
        long? DatagsmStudentId, long? RequestedBy, DateTime OccurredAt, int OccurredAtPrecision, string Provider, string ProviderSubject)
    {
        public bool IsProviderCommand => UserId is null;
    }

    /// <summary>The only command schema version accepted from the identity command topic.</summary>
    public const int CommandSchemaVersion = 2;

    public static Command? Parse(JsonElement payload, string? key)
    {
        var operationId = Uuid(payload.Get("operationId"));
        var idempotencyKey = ContractFields.String(payload.Get("idempotencyKey"));
        var userId = payload.Get("userId").Integer();
        if (!ContractFields.ExactKeys(payload, AllowedKeys)
            || payload.Get("schemaVersion").Integer() != CommandSchemaVersion
            || operationId is null
            || idempotencyKey is null || idempotencyKey.Trim() == "" || Accounts.TextLength(idempotencyKey) > MaxIdempotencyKeyLength
            || payload.GetString("commandType") != "UPSERT"
            || userId is not > 0
            || key != userId.Value.ToString(CultureInfo.InvariantCulture)
            || payload.Get("requestedBy").Integer() != userId
            || !ContractFields.RequiredString(payload.Get("name"), 50)
            || !ContractFields.RequiredString(payload.Get("email"), 255)
            || !ContractFields.RequiredString(payload.Get("sex"), 10)
            || !ContractFields.OptionalString(payload.Get("major"), 50)
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
            payload.GetString("major"), payload.GetString("role")!, payload.GetString("githubId"),
            payload.Get("dataGSMStudentId").Integer(), userId.Value, occurredAt, precision, "DATAGSM", key!);
    }

    static readonly HashSet<string> ProviderKeys =
        ["schemaVersion", "commandType", "operationId", "idempotencyKey", "provider", "providerSubject", "name", "email", "student", "occurredAt"];
    static readonly HashSet<string> StudentKeys =
        ["sex", "role", "grade", "classNumber", "studentNumberInClass", "major", "githubId", "dataGSMStudentId"];

    public static Command? ParseProvider(JsonElement payload, string? key)
    {
        var provider = payload.GetString("provider");
        var subject = payload.GetString("providerSubject");
        var operation = CorrelationOperationId(payload);
        if (!ContractFields.ExactKeys(payload, ProviderKeys) || payload.Get("schemaVersion").Integer() != 3
            || payload.GetString("commandType") != "UPSERT" || operation is null
            || !ContractFields.RequiredString(payload.Get("idempotencyKey"), MaxIdempotencyKeyLength)
            || !ContractFields.RequiredString(payload.Get("name"), 50) || !ContractFields.RequiredString(payload.Get("email"), 255)
            || subject is null || key != provider + ":" + subject
            || !Iso8601.TryParse(payload.GetString("occurredAt"), out var at, out var precision)) return null;

        var command = new Command(operation, payload.GetString("idempotencyKey")!, null, payload.GetString("name")!,
            payload.GetString("email")!, "", null, null, null, null, "", null, null, null, at, precision, provider!, subject);
        if (provider == "DEV")
            return subject is ("dev-1" or "dev-2" or "dev-3" or "dev-4") && payload.Get("student") is { ValueKind: JsonValueKind.Null }
                ? command : null;
        if (provider != "DATAGSM" || !long.TryParse(subject, NumberStyles.None, CultureInfo.InvariantCulture, out var number)
            || number <= 0 || subject != number.ToString(CultureInfo.InvariantCulture)
            || payload.Get("student") is not { ValueKind: JsonValueKind.Object } student
            || !ContractFields.ExactKeys(student, StudentKeys) || student.Get("dataGSMStudentId").Integer() is not > 0
            || !ContractFields.RequiredString(student.Get("sex"), 10) || !ContractFields.RequiredString(student.Get("role"), 50)
            || !ContractFields.OptionalInteger(student.Get("grade")) || !ContractFields.OptionalInteger(student.Get("classNumber"))
            || !ContractFields.OptionalInteger(student.Get("studentNumberInClass")) || !ContractFields.OptionalString(student.Get("major"), 50)
            || !ContractFields.OptionalString(student.Get("githubId"), 100)) return null;
        return command with
        {
            Sex = student.GetString("sex")!, Role = student.GetString("role")!, Grade = student.Get("grade").Integer(),
            ClassNumber = student.Get("classNumber").Integer(), StudentNumberInClass = student.Get("studentNumberInClass").Integer(),
            Major = student.GetString("major"), GithubId = student.GetString("githubId"), DatagsmStudentId = student.Get("dataGSMStudentId").Integer()
        };
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
        ["schemaVersion"] = CommandSchemaVersion,
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
}
