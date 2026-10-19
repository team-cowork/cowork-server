using System.Globalization;
using System.Text.Json;
using CoworkUser.Platform;

namespace CoworkUser.Kafka.Commands;

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
