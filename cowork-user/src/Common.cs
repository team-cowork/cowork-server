using System.Globalization;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.RegularExpressions;
using MySqlConnector;

namespace CoworkUser;

/// <summary>A failure mapped to an HTTP status and a user-facing message.</summary>
public sealed class ApiError(int status, string message) : Exception(message)
{
    public int Status { get; } = status;

    public static ApiError NotFound() => new(404, "사용자를 찾을 수 없습니다.");
    public static ApiError Validation(string message) => new(400, message);
}

/// <summary>Process-wide services, mirroring the single-node runtime the service was built around.</summary>
public static class App
{
    public static AppConfig Config { get; set; } = null!;
    public static MySqlDataSource Db { get; set; } = null!;
    public static ILoggerFactory Logs { get; set; } = null!;

    public static async Task<MySqlConnection> OpenAsync(CancellationToken cancellationToken = default) =>
        await Db.OpenConnectionAsync(cancellationToken);

    public static async Task<T?> FirstOrNullAsync<T>(this Task<IEnumerable<T>> query) where T : struct
    {
        foreach (var row in await query) return row;
        return null;
    }

    public static async Task<T> TransactionAsync<T>(Func<MySqlConnection, MySqlTransaction, Task<T>> work, CancellationToken cancellationToken = default)
    {
        await using var connection = await OpenAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var result = await work(connection, transaction);
        await transaction.CommitAsync(cancellationToken);
        return result;
    }
}

public static class Json
{
    // Jason, the previous encoder, wrote non-ASCII text without \u escapes.
    public static readonly JsonSerializerOptions Api = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static readonly JsonSerializerOptions Event = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    public static string Encode(object value) => JsonSerializer.Serialize(value, Event);

    /// <summary>Parses a JSON object payload; null for invalid JSON or a non-object document.</summary>
    public static JsonElement? ParseObject(byte[]? bytes, out string? error)
    {
        error = null;
        if (bytes is null)
        {
            error = ":non_binary_payload";
            return null;
        }
        try
        {
            using var document = JsonDocument.Parse(bytes);
            if (document.RootElement.ValueKind == JsonValueKind.Object) return document.RootElement.Clone();
            error = ":unexpected_payload";
            return null;
        }
        catch (JsonException exception)
        {
            error = $"{{:invalid_json, {exception.BytePositionInLine}}}";
            return null;
        }
    }

    public static JsonElement? Get(this JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) ? value : null;

    public static string? GetString(this JsonElement element, string name) =>
        element.Get(name) is { ValueKind: JsonValueKind.String } value ? value.GetString() : null;

    /// <summary>The first non-null value among the given keys, like `Map.get(a) || Map.get(b)`.</summary>
    public static JsonElement? GetEither(this JsonElement element, string camel, string? snake) =>
        element.Get(camel) is { ValueKind: not JsonValueKind.Null } value ? value
        : snake is null ? null
        : element.Get(snake) is { ValueKind: not JsonValueKind.Null } other ? other : null;

    /// <summary>A JSON integer (no fraction or exponent) that fits in a long.</summary>
    public static long? Integer(this JsonElement? element) =>
        element is { ValueKind: JsonValueKind.Number } number
        && !number.GetRawText().AsSpan().ContainsAny('.', 'e', 'E')
        && number.TryGetInt64(out var value) ? value : null;
}

public static partial class Iso8601
{
    [GeneratedRegex(@"\A(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:[.,](\d+))?(Z|[+-]\d{2}(?::?\d{2})?)?\z")]
    private static partial Regex Pattern();

    /// <summary>
    /// Parses an ISO 8601 date-time to UTC like Elixir's DateTime.from_iso8601: an offset is required
    /// unless <paramref name="assumeUtc"/>; fractional seconds keep up to microsecond precision.
    /// </summary>
    public static bool TryParse(string? value, out DateTime utc, out int precision, bool assumeUtc = false)
    {
        utc = default;
        precision = 0;
        var match = value is null ? null : Pattern().Match(value);
        if (match is not { Success: true } || (!assumeUtc && !match.Groups[8].Success)) return false;

        int Part(int group) => int.Parse(match.Groups[group].Value, CultureInfo.InvariantCulture);
        var fraction = match.Groups[7].Value;
        precision = Math.Min(fraction.Length, 6);
        var microseconds = precision == 0 ? 0 : int.Parse(fraction[..precision].PadRight(6, '0'), CultureInfo.InvariantCulture);

        try
        {
            var local = new DateTime(Part(1), Part(2), Part(3), Part(4), Part(5), Part(6), DateTimeKind.Unspecified)
                .AddTicks(microseconds * 10L);
            var offset = TimeSpan.Zero;
            var zone = match.Groups[8].Value;
            if (zone is not ("" or "Z"))
            {
                var digits = zone[1..].Replace(":", "");
                if (zone == "-00:00" || zone == "-0000" || zone == "-00") return false;
                offset = new TimeSpan(int.Parse(digits[..2], CultureInfo.InvariantCulture),
                    digits.Length > 2 ? int.Parse(digits[2..], CultureInfo.InvariantCulture) : 0, 0);
                if (zone[0] == '-') offset = -offset;
            }
            utc = DateTime.SpecifyKind(local - offset, DateTimeKind.Utc);
            return true;
        }
        catch (ArgumentOutOfRangeException)
        {
            return false;
        }
    }

    /// <summary>Formats like Elixir's DateTime.to_iso8601 for a UTC value.</summary>
    public static string Format(DateTime utc, int precision = 6)
    {
        var text = utc.ToString("yyyy-MM-ddTHH:mm:ss", CultureInfo.InvariantCulture);
        if (precision > 0)
            text += "." + (utc.Ticks % TimeSpan.TicksPerSecond / 10).ToString("D6", CultureInfo.InvariantCulture)[..precision];
        return text + "Z";
    }

    public static DateTime TruncateMicros(DateTime value) =>
        DateTime.SpecifyKind(new DateTime(value.Ticks - value.Ticks % 10), DateTimeKind.Utc);

    public static DateTime UtcNow() => TruncateMicros(DateTime.UtcNow);

    /// <summary>MySQL DATETIME values are stored in UTC.</summary>
    public static DateTime AsUtc(DateTime value) => DateTime.SpecifyKind(value, DateTimeKind.Utc);
}
