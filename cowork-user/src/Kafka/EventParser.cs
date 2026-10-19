using System.Globalization;
using System.Text.Json;
using CoworkUser.Platform;

namespace CoworkUser.Kafka;

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
