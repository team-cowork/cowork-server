using System.Globalization;
using System.Text.RegularExpressions;

namespace CoworkUser.Platform;

public static partial class Iso8601
{
    [GeneratedRegex(@"\A(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:[.,](\d{1,6})\d*)?(Z|[+-]\d{2}(?::?\d{2})?)?\z")]
    private static partial Regex Pattern();

    /// <summary>
    /// Parses a timestamp to UTC at microsecond precision. An offset is required unless
    /// <paramref name="assumeUtc"/> is set.
    /// </summary>
    public static bool TryParse(string? value, out DateTime utc, bool assumeUtc = false)
    {
        utc = default;
        var match = value is null ? null : Pattern().Match(value);
        if (match is not { Success: true } || (!assumeUtc && !match.Groups[8].Success)) return false;

        int Part(int group) => int.Parse(match.Groups[group].Value, CultureInfo.InvariantCulture);
        var microseconds = int.Parse(match.Groups[7].Value.PadRight(6, '0'), CultureInfo.InvariantCulture);

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
                var hours = int.Parse(digits[..2], CultureInfo.InvariantCulture);
                var minutes = digits.Length > 2 ? int.Parse(digits[2..], CultureInfo.InvariantCulture) : 0;
                // TimeSpan normalizes out-of-range hours and minutes.
                if (hours > 23 || minutes > 59) return false;
                offset = new TimeSpan(hours, minutes, 0);
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

    /// <summary>Kafka events and stored command hashes use six fractional digits in UTC.</summary>
    public static string Format(DateTime utc) =>
        utc.ToString("yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'", CultureInfo.InvariantCulture);

    public static DateTime TruncateMicros(DateTime value) =>
        new(value.Ticks - value.Ticks % 10, DateTimeKind.Utc);

    public static DateTime UtcNow() => TruncateMicros(DateTime.UtcNow);

    /// <summary>MySQL DATETIME values are stored in UTC.</summary>
    public static DateTime AsUtc(DateTime value) => DateTime.SpecifyKind(value, DateTimeKind.Utc);
}
