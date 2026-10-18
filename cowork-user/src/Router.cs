using System.Diagnostics;
using System.Globalization;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CoworkUser.Kafka;

namespace CoworkUser;

/// <summary>
/// HTTP routes. Every unmatched method or path answers 404 with a JSON message, and projection
/// dependent reads answer 503 until projections are ready.
/// </summary>
public static class Router
{
    static readonly byte[] OpenApiSpec = ReadResource("openapi.json");
    static readonly UTF8Encoding StrictUtf8 = new(false, true);

    static byte[] ReadResource(string name)
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)!;
        using var copy = new MemoryStream();
        stream.CopyTo(copy);
        return copy.ToArray();
    }

    public static async Task HandleAsync(HttpContext context, ProjectionReadiness readiness, ILogger logger)
    {
        var stopwatch = Stopwatch.StartNew();
        var request = context.Request;
        var requestId = request.Headers["x-request-id"].ToString() is { Length: >= 20 and <= 200 } incoming
            ? incoming
            : Convert.ToBase64String(RandomNumberGenerator.GetBytes(15)).Replace('+', '-').Replace('/', '_');
        context.Response.Headers["x-request-id"] = requestId;

        try
        {
            await DispatchAsync(context, readiness);
        }
        catch (ApiError error) when (!context.Response.HasStarted)
        {
            await WriteJsonAsync(context, error.Status, new { message = error.Message });
        }
        catch (FieldErrors errors) when (!context.Response.HasStarted)
        {
            await WriteJsonAsync(context, 400, new { message = errors.Message });
        }
        catch (Exception exception) when (!context.Response.HasStarted)
        {
            logger.LogError(exception, "Request failed request_id={RequestId}", requestId);
            context.Response.StatusCode = 500;
        }
        finally
        {
            Metrics.Record(request.Path.Value ?? "/", context.Response.StatusCode, stopwatch.Elapsed);
            logger.LogInformation("{Method} {Path} -> {Status} in {Elapsed}ms request_id={RequestId}",
                request.Method, request.Path.Value, context.Response.StatusCode, stopwatch.ElapsedMilliseconds, requestId);
        }
    }

    static async Task DispatchAsync(HttpContext context, ProjectionReadiness readiness)
    {
        var request = context.Request;
        var method = request.Method;
        var path = request.Path.Value ?? "/";
        string[] segments = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
        var body = method is "POST" or "PATCH" or "PUT" or "DELETE" ? await ReadBodyAsync(request) : default;

        if (method == "GET" && ProjectionDependentRead(path) && !readiness.Ready)
            throw new ApiError(503, "사용자 상태 투영을 동기화하는 중입니다.");

        switch (method, segments)
        {
            case ("GET", ["actuator", "health"]):
                await WriteJsonAsync(context, 200, new { status = "UP" });
                break;
            case ("GET", ["actuator", "health", "readiness"]):
                await WriteJsonAsync(context, readiness.Ready ? 200 : 503, new { status = readiness.Ready ? "UP" : "OUT_OF_SERVICE" });
                break;
            case ("GET", ["actuator", "prometheus"]):
                context.Response.ContentType = "text/plain; version=0.0.4";
                await context.Response.WriteAsync(Metrics.RenderPrometheus());
                break;
            case ("GET", ["v3", "api-docs"]):
                context.Response.ContentType = "application/json; charset=utf-8";
                await context.Response.Body.WriteAsync(OpenApiSpec);
                break;
            case ("GET", ["swagger-ui.html"]):
                context.Response.ContentType = "text/html; charset=utf-8";
                await context.Response.WriteAsync(SwaggerUi);
                break;
            case ("GET", ["users", "me"]):
                await WriteJsonAsync(context, 200, await Accounts.GetUserProfileAsync(UserId(request)));
                break;
            case ("PATCH", ["users", "me"]):
                await WriteJsonAsync(context, 200, await Accounts.UpdateMyProfileAsync(UserId(request), body));
                break;
            case ("PATCH", ["users", "me", "status"]):
                await WriteJsonAsync(context, 200, await Accounts.UpdateMyStatusAsync(UserId(request), body));
                break;
            case ("POST", ["users", "me", "profile-image", "presigned"]):
                await WriteJsonAsync(context, 200, await Accounts.GeneratePresignedUrlAsync(UserId(request), body));
                break;
            case ("POST", ["users", "me", "profile-image", "confirm"]):
                await Accounts.ConfirmUploadAsync(UserId(request), body);
                context.Response.StatusCode = 200;
                break;
            case ("DELETE", ["users", "me", "profile-image"]):
                await Accounts.DeleteProfileImageAsync(UserId(request));
                context.Response.StatusCode = 204;
                break;
            case ("GET", ["users", "search"]):
                var query = Query(request);
                if (query.ContainsKey("teamId") && !readiness.TeamReady) throw new ApiError(503, "팀 멤버 투영을 동기화하는 중입니다.");
                await WriteJsonAsync(context, 200, await Accounts.SearchUsersAsync(UserId(request), query));
                break;
            case ("GET", ["users", "batch"]):
                await WriteJsonAsync(context, 200, new { users = await Accounts.GetDisplayNamesAsync(ParseIds(Query(request).GetValueOrDefault("ids"))) });
                break;
            case ("GET", ["users", var userId]):
                await WriteJsonAsync(context, 200, await Accounts.GetUserProfileAsync(ParseInteger(userId, "user_id")));
                break;
            case ("GET", ["users", "by-github", var githubUsername]):
                await WriteJsonAsync(context, 200, await Accounts.GetByGithubAsync(githubUsername));
                break;
            default:
                throw new ApiError(404, "요청한 경로를 찾을 수 없습니다.");
        }
    }

    internal static bool ProjectionDependentRead(string path) =>
        path is "/users/me" or "/users/batch" or "/users/search"
        || (path.StartsWith("/users/by-github/", StringComparison.Ordinal) && path.Length > "/users/by-github/".Length)
        || (path.StartsWith("/users/", StringComparison.Ordinal) && TryParseInteger(path["/users/".Length..], out var id) && id > 0);

    static async Task<JsonElement> ReadBodyAsync(HttpRequest request)
    {
        var empty = JsonDocument.Parse("{}").RootElement;
        if (request.ContentType is null) return empty;
        if (!request.ContentType.Split(';')[0].Trim().Equals("application/json", StringComparison.OrdinalIgnoreCase))
            throw new ApiError(415, "지원하지 않는 요청 본문 형식입니다.");

        using var buffer = new MemoryStream();
        await request.Body.CopyToAsync(buffer);
        if (buffer.Length == 0) return empty;
        try
        {
            // JsonDocument defers UTF-8 validation of string values until they are read.
            using var document = JsonDocument.Parse(StrictUtf8.GetString(buffer.GetBuffer(), 0, (int)buffer.Length));
            // Plug wrapped non-object bodies under "_json", leaving no recognized fields.
            return document.RootElement.ValueKind == JsonValueKind.Object ? document.RootElement.Clone() : empty;
        }
        catch (Exception exception) when (exception is JsonException or DecoderFallbackException)
        {
            throw ApiError.Validation("요청 본문이 올바른 JSON 형식이 아닙니다.");
        }
    }

    static Dictionary<string, string> Query(HttpRequest request) =>
        request.Query.ToDictionary(pair => pair.Key, pair => pair.Value[^1] ?? "");

    static long UserId(HttpRequest request) =>
        request.Headers.TryGetValue("x-user-id", out var values) && values.Count > 0
            ? ParseInteger(values[0]!, "X-User-Id")
            : throw ApiError.Validation("X-User-Id 헤더가 누락되었습니다.");

    static bool TryParseInteger(string value, out long parsed) =>
        long.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out parsed);

    static long ParseInteger(string value, string fieldName) =>
        TryParseInteger(value, out var parsed) ? parsed : throw ApiError.Validation($"{fieldName} 값이 올바르지 않습니다.");

    internal static List<long> ParseIds(string? ids)
    {
        if (ids is null) throw ApiError.Validation("ids 파라미터가 필요합니다.");
        var parsed = Accounts.ParseIntCsv(ids).Distinct().Take(100).ToList();
        return parsed.Count == 0 ? throw ApiError.Validation("유효한 ids가 없습니다.") : parsed;
    }

    static async Task WriteJsonAsync(HttpContext context, int status, object body)
    {
        context.Response.StatusCode = status;
        context.Response.ContentType = "application/json; charset=utf-8";
        await JsonSerializer.SerializeAsync(context.Response.Body, body, body.GetType(), Json.Api);
    }

    const string SwaggerUi = """
        <!doctype html>
        <html lang="ko">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>cowork-user Swagger UI</title>
          <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5.33.0/swagger-ui.css" />
        </head>
        <body>
          <div id="swagger-ui"></div>
          <script src="https://unpkg.com/swagger-ui-dist@5.33.0/swagger-ui-bundle.js"></script>
          <script>
            window.ui = SwaggerUIBundle({
              url: "/v3/api-docs",
              dom_id: "#swagger-ui"
            });
          </script>
        </body>
        </html>

        """;
}
