using System.Globalization;
using System.Text.RegularExpressions;

namespace CoworkUser.Platform;

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
                var hours = int.Parse(digits[..2], CultureInfo.InvariantCulture);
                var minutes = digits.Length > 2 ? int.Parse(digits[2..], CultureInfo.InvariantCulture) : 0;
                // TimeSpan normalizes +99:99, so the zone range is checked here like Elixir does.
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
