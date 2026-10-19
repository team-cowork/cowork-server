using System.Text.Json;
using CoworkUser.Platform;
using CoworkUser.Users;

namespace CoworkUser.Kafka;

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
