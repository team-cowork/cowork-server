using System.Diagnostics;
using System.Globalization;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CoworkUser.Kafka.Projections;
using CoworkUser.Platform;
using CoworkUser.Users;

namespace CoworkUser.Http;

/// <summary>
/// HTTP endpoints. Every unmatched route answers 404 with a JSON message, and projection backed
/// reads answer 503 until projections are ready.
/// </summary>
public static class Router
{
    internal const string UnmatchedPattern = "{**path}";
    const string UnmatchedLabel = "NOT_FOUND";

    static readonly byte[] OpenApiSpec = ReadResource("openapi.json");
    static readonly UTF8Encoding StrictUtf8 = new(false, true);

    static byte[] ReadResource(string name)
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)!;
        using var copy = new MemoryStream();
        stream.CopyTo(copy);
        return copy.ToArray();
    }

    public static void Map(WebApplication app, ProjectionReadiness readiness)
    {
        RouteHandlerBuilder Gated(RouteHandlerBuilder route) => route.AddEndpointFilter(async (context, next) =>
            readiness.Ready ? await next(context) : throw new ApiError(503, "사용자 상태 투영을 동기화하는 중입니다."));

        app.MapGet("/actuator/health", () => Reply(new { status = "UP" }));
        app.MapGet("/actuator/health/readiness",
            () => Reply(new { status = readiness.Ready ? "UP" : "OUT_OF_SERVICE" }, readiness.Ready ? 200 : 503));
        app.MapGet("/actuator/prometheus", () => Results.Text(Metrics.RenderPrometheus(), "text/plain; version=0.0.4"));
        app.MapGet("/v3/api-docs", () => Results.Bytes(OpenApiSpec, "application/json; charset=utf-8"));
        app.MapGet("/swagger-ui.html", () => Results.Text(SwaggerUi, "text/html"));

        Gated(app.MapGet("/users/me", async (HttpRequest request) => Reply(await Accounts.GetUserProfileAsync(UserId(request)))));
        app.MapPatch("/users/me", async (HttpRequest request) =>
        {
            var body = await ReadBodyAsync(request);
            return Reply(await Accounts.UpdateMyProfileAsync(UserId(request), body));
        });
        app.MapPatch("/users/me/status", async (HttpRequest request) =>
        {
            var body = await ReadBodyAsync(request);
            return Reply(await Accounts.UpdateMyStatusAsync(UserId(request), body));
        });
        app.MapPost("/users/me/profile-image/presigned", async (HttpRequest request) =>
        {
            var body = await ReadBodyAsync(request);
            return Reply(await Accounts.GeneratePresignedUrlAsync(UserId(request), body));
        });
        app.MapPost("/users/me/profile-image/confirm", async (HttpRequest request) =>
        {
            var body = await ReadBodyAsync(request);
            await Accounts.ConfirmUploadAsync(UserId(request), body);
            return Results.Ok();
        });
        app.MapDelete("/users/me/profile-image", async (HttpRequest request) =>
        {
            await ReadBodyAsync(request);
            await Accounts.DeleteProfileImageAsync(UserId(request));
            return Results.NoContent();
        });
        Gated(app.MapGet("/users/search", async (HttpRequest request) =>
        {
            var query = Query(request);
            if (query.ContainsKey("teamId") && !readiness.TeamReady) throw new ApiError(503, "팀 멤버 투영을 동기화하는 중입니다.");
            return Reply(await Accounts.SearchUsersAsync(UserId(request), query));
        }));
        Gated(app.MapGet("/users/batch", async (HttpRequest request) =>
            Reply(new { users = await Accounts.GetDisplayNamesAsync(ParseIds(Query(request).GetValueOrDefault("ids"))) })));
        Gated(app.MapGet("/users/by-github/{githubUsername}", async (string githubUsername) =>
            Reply(await Accounts.GetByGithubAsync(githubUsername))));
        Gated(app.MapGet("/users/{userId}", async (string userId) =>
            Reply(await Accounts.GetUserProfileAsync(ParseInteger(userId, "user_id")))));

        app.MapFallback(UnmatchedPattern, () => Reply(new { message = "요청한 경로를 찾을 수 없습니다." }, 404));
    }

    public static async Task HandleAsync(HttpContext context, RequestDelegate next, ILogger logger)
    {
        var stopwatch = Stopwatch.StartNew();
        var request = context.Request;
        var requestId = request.Headers["x-request-id"].ToString() is { Length: >= 20 and <= 200 } incoming
            ? incoming
            : Convert.ToBase64String(RandomNumberGenerator.GetBytes(15)).Replace('+', '-').Replace('/', '_');
        context.Response.Headers["x-request-id"] = requestId;

        try
        {
            await next(context);
        }
        catch (ApiError error) when (!context.Response.HasStarted)
        {
            await Reply(new { message = error.Message }, error.Status).ExecuteAsync(context);
        }
        catch (FieldErrors errors) when (!context.Response.HasStarted)
        {
            await Reply(new { message = errors.Message }, 400).ExecuteAsync(context);
        }
        catch (BadHttpRequestException error) when (!context.Response.HasStarted)
        {
            // Kestrel's own client errors, such as 413 for a body over MaxRequestBodySize, keep their status.
            context.Response.StatusCode = error.StatusCode;
        }
        catch (Exception exception) when (!context.Response.HasStarted)
        {
            logger.LogError(exception, "Request failed request_id={RequestId}", requestId);
            context.Response.StatusCode = 500;
        }
        finally
        {
            Metrics.Record(RouteLabel(context), context.Response.StatusCode, stopwatch.Elapsed);
            logger.LogInformation("{Method} {Path} -> {Status} in {Elapsed}ms request_id={RequestId}",
                request.Method, request.Path.Value, context.Response.StatusCode, stopwatch.ElapsedMilliseconds, requestId);
        }
    }

    // The route template keeps the metric's uri label bounded; unmatched paths share one value.
    static string RouteLabel(HttpContext context) =>
        context.GetEndpoint() is RouteEndpoint { RoutePattern.RawText: { } pattern } && pattern != UnmatchedPattern ? pattern : UnmatchedLabel;

    static IResult Reply(object body, int status = 200) =>
        Results.Text(JsonSerializer.Serialize(body, body.GetType(), Json.Api), "application/json", Encoding.UTF8, status);

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
            if (document.RootElement.ValueKind != JsonValueKind.Object) return empty;
            Json.RequireDecodableStrings(document.RootElement);
            return document.RootElement.Clone();
        }
        catch (Exception exception) when (exception is JsonException or DecoderFallbackException or InvalidOperationException)
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

    static long ParseInteger(string value, string fieldName) =>
        long.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var parsed)
            ? parsed
            : throw ApiError.Validation($"{fieldName} 값이 올바르지 않습니다.");

    internal static List<long> ParseIds(string? ids)
    {
        if (ids is null) throw ApiError.Validation("ids 파라미터가 필요합니다.");
        var parsed = Accounts.ParseIntCsv(ids).Distinct().Take(100).ToList();
        return parsed.Count == 0 ? throw ApiError.Validation("유효한 ids가 없습니다.") : parsed;
    }

    const string SwaggerUi = """
        <!doctype html>
        <html lang="ko">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>cowork User API</title>
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
