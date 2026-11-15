using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CoworkUser.Kafka.Projections;
using CoworkUser.Platform;
using CoworkUser.Users;
using Microsoft.AspNetCore.Mvc;
using ParameterLocation = Microsoft.OpenApi.ParameterLocation;

namespace CoworkUser.Http;

/// <summary>
/// HTTP endpoints. Every unmatched route answers 404 with a JSON message, and projection backed
/// reads answer 503 until projections are ready.
/// </summary>
public static class Router
{
    internal const string UnmatchedPattern = "{**path}";
    const string UnmatchedLabel = "NOT_FOUND";

    static readonly UTF8Encoding StrictUtf8 = new(false, true);

    public static void Map(WebApplication app, ProjectionReadiness readiness)
    {
        RouteHandlerBuilder Gated(RouteHandlerBuilder route) => route.WithMetadata(new ErrorsAttribute(503)).AddEndpointFilter(async (context, next) =>
            readiness.Ready ? await next(context) : throw new ApiError(503, "사용자 상태 투영을 동기화하는 중입니다."));

        app.MapGet("/actuator/health", () => Reply(new { status = "UP" })).ExcludeFromDescription();
        app.MapGet("/actuator/health/readiness",
            () => Reply(new { status = readiness.Ready ? "UP" : "OUT_OF_SERVICE" }, readiness.Ready ? 200 : 503)).ExcludeFromDescription();
        app.MapGet("/actuator/prometheus", () => Results.Text(Metrics.RenderPrometheus(), "text/plain; version=0.0.4")).ExcludeFromDescription();
        app.MapOpenApi("/v3/api-docs");
        app.MapGet("/swagger-ui.html", () => Results.Text(SwaggerUi, "text/html")).ExcludeFromDescription();

        Gated(app.MapGet("/users/me", GetMyProfileAsync));
        app.MapPatch("/users/me", UpdateMyProfileAsync);
        app.MapPatch("/users/me/status", UpdateMyStatusAsync);
        app.MapPost("/users/me/profile-image/presigned", GeneratePresignedUrlAsync);
        app.MapPost("/users/me/profile-image/confirm", ConfirmUploadAsync);
        app.MapDelete("/users/me/profile-image", DeleteProfileImageAsync);
        Gated(app.MapGet("/users/search", SearchUsersAsync));
        Gated(app.MapGet("/users/batch", GetDisplayNamesAsync));
        Gated(app.MapGet("/users/by-github/{githubUsername}", GetByGithubAsync));
        Gated(app.MapGet("/users/{userId}", GetUserProfileAsync));

        app.MapFallback(UnmatchedPattern, () => Reply(new { message = "요청한 경로를 찾을 수 없습니다." }, 404)).ExcludeFromDescription();
    }

    [Tags(ApiDocs.Profile), EndpointSummary("내 프로필 조회")]
    [Ok<UserResponse>("사용자 프로필"), Errors(404)]
    static async Task<IResult> GetMyProfileAsync(HttpRequest request) =>
        Reply(await Accounts.GetUserProfileAsync(UserId(request)));

    [Tags(ApiDocs.Profile), EndpointSummary("내 프로필 수정")]
    [RequestBody<UpdateProfileRequest>, Ok<UserResponse>("사용자 프로필"), Errors(404)]
    static async Task<IResult> UpdateMyProfileAsync(HttpRequest request)
    {
        var body = await ReadBodyAsync(request);
        return Reply(await Accounts.UpdateMyProfileAsync(UserId(request), body));
    }

    [Tags(ApiDocs.Status), EndpointSummary("사용자 상태와 상태 메시지 설정")]
    [EndpointDescription("message와 expiresAt은 생략하면 기존 값을 유지하고 null을 보내면 지웁니다.")]
    [RequestBody<UpdateStatusRequest>, Ok<UserResponse>("사용자 프로필"), Errors(404)]
    static async Task<IResult> UpdateMyStatusAsync(HttpRequest request)
    {
        var body = await ReadBodyAsync(request);
        return Reply(await Accounts.UpdateMyStatusAsync(UserId(request), body));
    }

    [Tags(ApiDocs.ProfileImage), EndpointSummary("프로필 이미지 업로드 URL 발급")]
    [RequestBody<PresignedUrlRequest>, Ok<PresignedUpload>("파일 업로드 URL과 객체 키"), Errors(404)]
    static async Task<IResult> GeneratePresignedUrlAsync(HttpRequest request)
    {
        var body = await ReadBodyAsync(request);
        return Reply(await Accounts.GeneratePresignedUrlAsync(UserId(request), body));
    }

    [Tags(ApiDocs.ProfileImage), EndpointSummary("프로필 이미지 업로드 확인")]
    [RequestBody<ConfirmUploadRequest>, Empty(200), Errors(404, 413), Error(409, "업로드한 파일이 없거나 변경됨")]
    static async Task<IResult> ConfirmUploadAsync(HttpRequest request)
    {
        var body = await ReadBodyAsync(request);
        await Accounts.ConfirmUploadAsync(UserId(request), body);
        return Results.Ok();
    }

    [Tags(ApiDocs.ProfileImage), EndpointSummary("프로필 이미지 삭제")]
    [Empty(204), Errors(400, 404, 415), Error(409, "다른 요청이 프로필 이미지를 먼저 변경함")]
    static async Task<IResult> DeleteProfileImageAsync(HttpRequest request)
    {
        await ReadBodyAsync(request);
        await Accounts.DeleteProfileImageAsync(UserId(request));
        return Results.NoContent();
    }

    [Tags(ApiDocs.Lookup), EndpointSummary("사용자 검색")]
    [Ok<SearchResult>("페이지별 검색 결과"), Errors(400), Error(403, "팀 멤버만 검색할 수 있음")]
    [Param("teamId", typeof(long), "지정한 팀의 멤버만 검색합니다. 요청자도 해당 팀의 멤버여야 합니다.", Minimum = "1")]
    [Param("q", typeof(string), "이름이나 닉네임의 일부로 검색합니다. 대소문자를 구분하지 않으며 %, _ 및 역슬래시는 일반 문자로 취급합니다.")]
    [Param("query", typeof(string), "q를 생략하거나 공백으로 보내면 대신 사용합니다.")]
    [Param("name", typeof(string), "이름의 일부로 검색합니다. 대소문자를 구분하지 않습니다.")]
    [Param("nickname", typeof(string), "닉네임의 일부로 검색합니다. 대소문자를 구분하지 않습니다.")]
    [Param("major", typeof(string)), Param("student_role", typeof(string))]
    [Param("status", typeof(string), Enum = ["online", "offline"])]
    [Param("custom_status", typeof(string), MaxLength = 30)]
    [Param("role", typeof(string))]
    [Param("page", typeof(int), Default = 1)]
    [Param("page_size", typeof(int), "페이지당 최대 100명까지 반환합니다.", Default = 20)]
    [Param("sort_by", typeof(string), "id, name, nickname 또는 major로 정렬합니다. 그 밖의 값은 id로 처리합니다.", Default = "id")]
    [Param("sort_order", typeof(string), Enum = ["asc", "desc"], Default = "asc")]
    static async Task<IResult> SearchUsersAsync(HttpRequest request, [FromServices] ProjectionReadiness readiness)
    {
        var query = Query(request);
        if (query.ContainsKey("teamId") && !readiness.TeamReady) throw new ApiError(503, "팀 멤버 투영을 동기화하는 중입니다.");
        return Reply(await Accounts.SearchUsersAsync(UserId(request), query));
    }

    [Tags(ApiDocs.Lookup), EndpointSummary("사용자 표시 이름 일괄 조회")]
    [Ok<DisplayNamesResponse>("사용자 표시 이름 목록"), Errors(400)]
    [Param("ids", typeof(string), "쉼표로 구분한 사용자 ID입니다. 양의 정수만 사용하며 중복을 제외하고 최대 100개까지 조회합니다.", Required = true)]
    static async Task<IResult> GetDisplayNamesAsync(HttpRequest request) =>
        Reply(new DisplayNamesResponse(await Accounts.GetDisplayNamesAsync(ParseIds(Query(request).GetValueOrDefault("ids")))));

    [Tags(ApiDocs.Lookup), EndpointSummary("GitHub 사용자 이름으로 프로필 조회")]
    [Ok<UserResponse>("사용자 프로필"), Errors(404)]
    static async Task<IResult> GetByGithubAsync(string githubUsername) =>
        Reply(await Accounts.GetByGithubAsync(githubUsername));

    // userId binds as a string so that a malformed value gets the service's own 400 message.
    [Tags(ApiDocs.Lookup), EndpointSummary("사용자 조회")]
    [Ok<UserResponse>("사용자 프로필"), Errors(400, 404), Param("userId", typeof(long), In = ParameterLocation.Path)]
    static async Task<IResult> GetUserProfileAsync(string userId) =>
        Reply(await Accounts.GetUserProfileAsync(ParseInteger(userId, "user_id")));

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
