using System.Text.Encodings.Web;
using System.Text.Json;

namespace CoworkUser.Platform;

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

    /// <summary>Parses a JSON object payload; null for invalid JSON, undecodable strings or a non-object document.</summary>
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
            if (document.RootElement.ValueKind != JsonValueKind.Object)
            {
                error = ":unexpected_payload";
                return null;
            }
            RequireDecodableStrings(document.RootElement);
            return document.RootElement.Clone();
        }
        catch (JsonException exception)
        {
            error = $"{{:invalid_json, {exception.BytePositionInLine}}}";
            return null;
        }
        catch (InvalidOperationException)
        {
            // Invalid UTF-8 or a lone surrogate escape passes the parser and only fails when the string is read.
            error = ":invalid_string";
            return null;
        }
    }

    internal static void RequireDecodableStrings(JsonElement element)
    {
        switch (element.ValueKind)
        {
            case JsonValueKind.String:
                element.GetString();
                break;
            case JsonValueKind.Array:
                foreach (var item in element.EnumerateArray()) RequireDecodableStrings(item);
                break;
            case JsonValueKind.Object:
                foreach (var property in element.EnumerateObject())
                {
                    _ = property.Name;
                    RequireDecodableStrings(property.Value);
                }
                break;
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
