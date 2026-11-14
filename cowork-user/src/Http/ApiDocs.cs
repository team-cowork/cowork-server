using System.ComponentModel;
using System.ComponentModel.DataAnnotations;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using CoworkUser.Platform;
using CoworkUser.Users;
using Microsoft.AspNetCore.Http.Metadata;
using Microsoft.OpenApi;

namespace CoworkUser.Http;

/// <summary>
/// Builds the OpenAPI 3.2 document from the attributes on the endpoint handlers. The gateway wraps every
/// successful body in <see cref="Envelope{T}"/> and every failure in the Error schema, so the document shows
/// those shapes. The gateway can also answer 401 and 429, so every operation lists them.
/// </summary>
public static class ApiDocs
{
    public const string Profile = "내 프로필";
    public const string Status = "사용자 상태";
    public const string ProfileImage = "프로필 이미지";
    public const string Lookup = "사용자 조회";

    static readonly (string Name, string Description)[] Tags =
    [
        (Profile, "내 프로필 정보를 조회하고 수정합니다."),
        (Status, "내 상태와 상태 메시지를 설정합니다."),
        (ProfileImage, "프로필 이미지를 업로드하고 삭제합니다."),
        (Lookup, "사용자 프로필과 표시 이름을 조회하고 사용자를 검색합니다."),
    ];

    internal static readonly SortedDictionary<int, (string Status, string Description)> ErrorCatalog = new()
    {
        [400] = ("400 BAD_REQUEST", "요청 값이 올바르지 않음"),
        [401] = ("401 UNAUTHORIZED", "액세스 토큰이 없거나 유효하지 않음"),
        [403] = ("403 FORBIDDEN", "접근 권한 없음"),
        [404] = ("404 NOT_FOUND", "사용자 또는 프로필 없음"),
        [409] = ("409 CONFLICT", "요청이 현재 상태와 충돌함"),
        [413] = ("413 CONTENT_TOO_LARGE", "파일 크기 제한 초과"),
        [415] = ("415 UNSUPPORTED_MEDIA_TYPE", "요청 본문이 application/json 형식이 아님"),
        [429] = ("429 TOO_MANY_REQUESTS", "요청 한도 초과"),
        [503] = ("503 SERVICE_UNAVAILABLE", "사용자 또는 팀 멤버 정보를 아직 조회할 수 없음"),
    };

    internal static readonly string Version =
        typeof(ApiDocs).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()!.InformationalVersion.Split('+')[0];

    public static IServiceCollection AddApiDocs(this IServiceCollection services)
    {
        // Only the document generator reads these options; handlers serialize with Json.Api.
        services.ConfigureHttpJsonOptions(options =>
        {
            options.SerializerOptions.PropertyNamingPolicy = Json.Api.PropertyNamingPolicy;
            options.SerializerOptions.NumberHandling = JsonNumberHandling.Strict;
            options.SerializerOptions.RespectNullableAnnotations = true;
            options.SerializerOptions.RespectRequiredConstructorParameters = true;
        });
        return services.AddOpenApi(options =>
        {
            // TODO: Upgrade the package to write OpenAPI 3.2.1 once Microsoft.OpenApi outputs that document version.
            //  Microsoft.OpenApi 3.x fixes the OpenAPI 3.2 document version at "3.2.0".
            options.OpenApiVersion = OpenApiSpecVersion.OpenApi3_2;
            options.AddSchemaTransformer((schema, context, _) =>
            {
                var attributes = context.JsonPropertyInfo?.AttributeProvider;
                if (attributes?.GetCustomAttributes(typeof(ExampleAttribute), false) is [ExampleAttribute example, ..])
                    schema.Examples = [JsonSerializer.SerializeToNode(example.Value)!];
                if (attributes?.GetCustomAttributes(typeof(AllowedValuesAttribute), false) is [AllowedValuesAttribute allowed, ..])
                    schema.Enum = allowed.Values.Select(value => JsonSerializer.SerializeToNode(value)!).ToList();
                return Task.CompletedTask;
            });
            options.AddOperationTransformer((operation, context, _) =>
            {
                Describe(operation, context.Description.ActionDescriptor.EndpointMetadata);
                return Task.CompletedTask;
            });
            options.AddDocumentTransformer((document, _, _) =>
            {
                Complete(document);
                return Task.CompletedTask;
            });
        });
    }

    static void Describe(OpenApiOperation operation, IList<object> metadata)
    {
        foreach (var param in metadata.OfType<ParamAttribute>())
        {
            operation.Parameters ??= [];
            if (operation.Parameters.FirstOrDefault(existing => existing.Name == param.Name && existing.In == param.In) is { } inferred)
                operation.Parameters.Remove(inferred);
            operation.Parameters.Add(param.ToParameter());
        }

        operation.Responses ??= new OpenApiResponses();
        foreach (var response in metadata.OfType<IDescribedResponse>())
            if (operation.Responses.TryGetValue(response.StatusCode.ToString(), out var documented) && documented is OpenApiResponse described)
                described.Description = response.Description;

        var errors = new SortedDictionary<int, string>();
        void Add(int code) => errors[code] = ErrorCatalog[code].Description;
        Add(401);
        Add(429);
        if (operation.RequestBody is not null)
        {
            Add(400);
            Add(415);
        }
        foreach (var code in metadata.OfType<ErrorsAttribute>().SelectMany(attribute => attribute.Codes)) Add(code);
        foreach (var error in metadata.OfType<ErrorAttribute>()) errors[error.Code] = error.Description;
        foreach (var (code, description) in errors)
            operation.Responses[code.ToString()] = new OpenApiResponse
            {
                Description = description,
                Content = new Dictionary<string, IOpenApiMediaType>
                {
                    ["application/json"] = new OpenApiMediaType { Schema = new OpenApiSchemaReference($"Error{code}") },
                },
            };
    }

    static void Complete(OpenApiDocument document)
    {
        document.Info = new OpenApiInfo
        {
            Title = "cowork User API",
            Version = Version,
            Description = "사용자 프로필, 상태, 프로필 이미지와 사용자 검색을 관리합니다.",
        };
        document.Servers = [new OpenApiServer { Url = "/api/user", Description = "Gateway" }];
        document.Tags = new HashSet<OpenApiTag>(Tags.Select(tag => new OpenApiTag { Name = tag.Name, Description = tag.Description }));
        document.Components ??= new OpenApiComponents();
        document.Components.SecuritySchemes = new Dictionary<string, IOpenApiSecurityScheme>
        {
            ["BearerAuth"] = new OpenApiSecurityScheme
            {
                Type = SecuritySchemeType.Http, Scheme = "bearer", BearerFormat = "JWT", Description = "cowork 액세스 토큰",
            },
        };
        document.Security = [new OpenApiSecurityRequirement { [new OpenApiSecuritySchemeReference("BearerAuth", document)] = [] }];
        document.Components.Schemas ??= new Dictionary<string, IOpenApiSchema>();
        document.Components.Schemas["Error"] = new OpenApiSchema
        {
            Type = JsonSchemaType.Object,
            Required = new HashSet<string> { "status", "code", "message" },
            Properties = new Dictionary<string, IOpenApiSchema>
            {
                ["status"] = new OpenApiSchema { Type = JsonSchemaType.String, Description = "HTTP 상태 코드와 이름입니다. 예: 404 NOT_FOUND." },
                ["code"] = new OpenApiSchema { Type = JsonSchemaType.Integer, Description = "HTTP 상태 코드" },
                ["message"] = new OpenApiSchema { Type = JsonSchemaType.String },
            },
        };
        foreach (var (code, (status, _)) in ErrorCatalog)
            document.Components.Schemas[$"Error{code}"] = new OpenApiSchema
            {
                AllOf =
                [
                    new OpenApiSchemaReference("Error", document),
                    new OpenApiSchema
                    {
                        Type = JsonSchemaType.Object,
                        Properties = new Dictionary<string, IOpenApiSchema>
                        {
                            ["status"] = new OpenApiSchema { Type = JsonSchemaType.String, Enum = [status] },
                            ["code"] = new OpenApiSchema { Type = JsonSchemaType.Integer, Enum = [code] },
                        },
                    },
                ],
            };
    }
}

interface IDescribedResponse
{
    int StatusCode { get; }
    string Description { get; }
}

/// <summary>Documents a 200 response whose body the gateway wraps in <see cref="Envelope{T}"/>.</summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class OkAttribute<TData>(string description) : Attribute, IProducesResponseTypeMetadata, IDescribedResponse
    where TData : notnull
{
    public Type Type => typeof(Envelope<TData>);
    public int StatusCode => 200;
    public string Description { get; } = description;
    public IEnumerable<string> ContentTypes { get; } = ["application/json"];
}

/// <summary>Documents a response without a body.</summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class EmptyAttribute(int statusCode) : Attribute, IProducesResponseTypeMetadata, IDescribedResponse
{
    public Type Type => typeof(void);
    public int StatusCode { get; } = statusCode;
    public string Description => "응답 본문 없음";
    public IEnumerable<string> ContentTypes { get; } = [];
}

/// <summary>
/// Documents a JSON request body. Handlers read the raw body so that they can tell an omitted field from an
/// explicit null, so <typeparamref name="TBody"/> only shapes the document. The operation also lists 400 and 415.
/// </summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class RequestBodyAttribute<TBody> : Attribute, IAcceptsMetadata
{
    public IReadOnlyList<string> ContentTypes { get; } = ["application/json"];
    public Type RequestType => typeof(TBody);
    public bool IsOptional => false;
}

/// <summary>Lists error responses with the default description of each status code.</summary>
[AttributeUsage(AttributeTargets.Method, AllowMultiple = true)]
public sealed class ErrorsAttribute(params int[] codes) : Attribute
{
    public int[] Codes { get; } = codes;
}

/// <summary>Lists an error response with an endpoint-specific description.</summary>
[AttributeUsage(AttributeTargets.Method, AllowMultiple = true)]
public sealed class ErrorAttribute(int code, string description) : Attribute
{
    public int Code { get; } = code;
    public string Description { get; } = description;
}

/// <summary>
/// Documents a parameter the handler reads itself. It replaces an inferred parameter with the same name,
/// such as a path value that the handler parses so that it can answer with its own 400 message.
/// </summary>
[AttributeUsage(AttributeTargets.Method, AllowMultiple = true)]
public sealed class ParamAttribute(string name, Type type, string? description = null) : Attribute
{
    public string Name { get; } = name;
    public ParameterLocation In { get; init; } = ParameterLocation.Query;
    public bool Required { get; init; }
    public string? Minimum { get; init; }
    public int MaxLength { get; init; }
    public string[]? Enum { get; init; }
    public object? Default { get; init; }

    internal OpenApiParameter ToParameter() => new()
    {
        Name = Name,
        In = In,
        Required = Required || In == ParameterLocation.Path,
        Description = description,
        Schema = new OpenApiSchema
        {
            Type = type == typeof(string) ? JsonSchemaType.String : JsonSchemaType.Integer,
            Format = type == typeof(long) ? "int64" : null,
            Minimum = Minimum,
            MaxLength = MaxLength > 0 ? MaxLength : null,
            Enum = Enum?.Select(value => JsonSerializer.SerializeToNode(value)!).ToList(),
            Default = Default is null ? null : JsonSerializer.SerializeToNode(Default),
        },
    };
}

/// <summary>Sets the example value of a documented property.</summary>
[AttributeUsage(AttributeTargets.Property)]
public sealed class ExampleAttribute(object value) : Attribute
{
    public object Value { get; } = value;
}

/// <summary>The gateway's success wrapper around a response body.</summary>
public sealed record Envelope<T>(
    [property: Example("200 OK")] string Status,
    [property: Example(200)] int Code,
    [property: Example("OK")] string Message,
    T Data) where T : notnull;

public sealed record DisplayNamesResponse(IReadOnlyList<DisplayName> Users);

// The request types below only shape the document. See RequestBodyAttribute.

public sealed class UpdateProfileRequest
{
    [Description("생략하면 기존 값을 유지하고 null이면 값을 비웁니다.")]
    public string? Nickname { get; init; }

    public string Name { get; init; } = "";

    [Description("생략하면 기존 값을 유지하고 null이면 값을 비웁니다.")]
    public string? Description { get; init; }

    [Description("수정할 수 없습니다. DataGSM 값만 사용하며, 현재 값과 같으면 무시하고 다르면 400을 반환합니다.")]
    public string? GithubId { get; init; }

    [Description("생략하면 기존 역할을 유지하고 빈 배열이면 역할을 모두 해제합니다.")]
    public IReadOnlyList<string> Roles { get; init; } = [];
}

public sealed class UpdateStatusRequest
{
    [MaxLength(30), Example("DO_NOT_DISTURB")]
    public required string CustomStatus { get; init; }

    [MaxLength(100), Example("집중 중")]
    public string? Message { get; init; }

    [JsonPropertyName("expiresAt"), Example("2026-05-26T18:00:00Z")]
    public DateTime? ExpiresAt { get; init; }
}

public sealed class PresignedUrlRequest
{
    public required string ContentType { get; init; }
}

public sealed class ConfirmUploadRequest
{
    public required string ObjectKey { get; init; }
}
