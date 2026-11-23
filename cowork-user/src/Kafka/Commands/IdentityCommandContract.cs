using System.Globalization;
using System.Text.Json;
using CoworkUser.Platform;

namespace CoworkUser.Kafka.Commands;

public static class IdentityCommandContract
{
    const int MaxIdempotencyKeyLength = 128;

    public sealed record Command(
        string OperationId, string IdempotencyKey, string Name, string Email, string Sex,
        long? Grade, long? ClassNumber, long? StudentNumberInClass, string? Major, string Role, string? GithubId,
        long? DatagsmStudentId, DateTime OccurredAt, int OccurredAtPrecision, string Provider, string ProviderSubject);

    /// <summary>The only command schema version accepted from the identity command topic.</summary>
    public const int CommandSchemaVersion = 3;

    static readonly HashSet<string> CommandKeys =
        ["schemaVersion", "commandType", "operationId", "idempotencyKey", "provider", "providerSubject", "name", "email", "student", "occurredAt"];
    static readonly HashSet<string> StudentKeys =
        ["sex", "role", "grade", "classNumber", "studentNumberInClass", "major", "githubId", "dataGSMStudentId"];

    public static Command? Parse(JsonElement payload, string? key)
    {
        var provider = payload.GetString("provider");
        var subject = payload.GetString("providerSubject");
        var operation = CorrelationOperationId(payload);
        if (!ContractFields.ExactKeys(payload, CommandKeys) || payload.Get("schemaVersion").Integer() != CommandSchemaVersion
            || payload.GetString("commandType") != "UPSERT" || operation is null
            || !ContractFields.RequiredString(payload.Get("idempotencyKey"), MaxIdempotencyKeyLength)
            || !ContractFields.RequiredString(payload.Get("name"), 50) || !ContractFields.RequiredString(payload.Get("email"), 255)
            || subject is null || key != provider + ":" + subject
            || !Iso8601.TryParse(payload.GetString("occurredAt"), out var at, out var precision)) return null;

        var command = new Command(operation, payload.GetString("idempotencyKey")!, payload.GetString("name")!,
            payload.GetString("email")!, "", null, null, null, null, "", null, null, at, precision, provider!, subject);
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

    /// <summary>Version of the command hash rule; inbox rows record it so a rule change never invalidates stored hashes.</summary>
    public const int HashVersion = 3;
}
