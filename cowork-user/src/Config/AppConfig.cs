using MySqlConnector;

namespace CoworkUser.Config;

public sealed record AppConfig
{
    public const string ProviderCommandTopic = "user.identity.command.v3";
    public const string ProviderCommandGroup = "cowork-user.user-identity-command.v3";
    public const string ProviderResultTopic = "user.identity.result.v2";

    public required int Port { get; init; }
    public required KafkaSecurity KafkaSecurity { get; init; }
    public required string ConnectionString { get; init; }
    public required string ConfigProfile { get; init; }
    public required string KafkaBootstrapServers { get; init; }
    public required string KafkaTopic { get; init; }
    public required string KafkaGroupId { get; init; }
    public required string KafkaTeamMemberTopic { get; init; }
    public required string KafkaTeamMemberGroupId { get; init; }
    public required string KafkaPresenceTopic { get; init; }
    public required string KafkaPresenceGroupId { get; init; }
    public required string KafkaProfileTopic { get; init; }
    public required int KafkaProfileSnapshotIntervalMs { get; init; }
    public required string S3Region { get; init; }
    public required string S3InternalEndpoint { get; init; }
    public required string PublicApiBaseUrl { get; init; }
    public required string S3AccessKey { get; init; }
    public required string S3SecretKey { get; init; }
    public required string S3Bucket { get; init; }
    public required bool S3PathStyle { get; init; }
    public required int PresignedPutExpiryMinutes { get; init; }
    public required int PresignedGetExpiryMinutes { get; init; }
    public required long MaxFileSizeBytes { get; init; }
    public required string RedisHost { get; init; }
    public required int RedisPort { get; init; }

    public static AppConfig Load()
    {
        var config = Build();
        ValidateStorage(config);
        return config;
    }

    static AppConfig Build()
    {
        return new AppConfig
        {
            Port = int.Parse(Optional("PORT", "8082")),
            KafkaSecurity = CoworkUser.Config.KafkaSecurity.Load(),
            ConnectionString = DatabaseConnectionString(),
            ConfigProfile = Required("APP_CONFIG_PROFILE"),
            KafkaBootstrapServers = Required("KAFKA_BOOTSTRAP_SERVERS"),
            KafkaTopic = Optional("KAFKA_TOPIC_USER_SYNC", "user.data.sync"),
            KafkaGroupId = Optional("KAFKA_GROUP_ID", "cowork-user"),
            KafkaTeamMemberTopic = Optional("KAFKA_TOPIC_TEAM_MEMBER", "team.member.event"),
            KafkaTeamMemberGroupId = Optional("KAFKA_GROUP_ID_TEAM_MEMBER", "cowork-user.team-member"),
            KafkaPresenceTopic = Optional("KAFKA_TOPIC_USER_PRESENCE", "user.presence.event"),
            KafkaPresenceGroupId = Optional("KAFKA_GROUP_ID_USER_PRESENCE", "cowork-user.user-presence"),
            KafkaProfileTopic = Optional("KAFKA_TOPIC_USER_PROFILE", "user.profile.event.v2"),
            KafkaProfileSnapshotIntervalMs = int.Parse(Optional("KAFKA_PROFILE_SNAPSHOT_INTERVAL_MS", "300000")),
            S3Region = Required("S3_REGION"),
            S3InternalEndpoint = Required("S3_INTERNAL_ENDPOINT"),
            PublicApiBaseUrl = Required("PUBLIC_API_BASE_URL"),
            S3AccessKey = Required("S3_ACCESS_KEY"),
            S3SecretKey = Required("S3_SECRET_KEY"),
            S3Bucket = Required("S3_BUCKET"),
            S3PathStyle = Optional("S3_PATH_STYLE_ACCESS_ENABLED", "true") == "true",
            PresignedPutExpiryMinutes = int.Parse(Optional("S3_PRESIGNED_PUT_EXPIRY_MINUTES", "5")),
            PresignedGetExpiryMinutes = int.Parse(Optional("S3_PRESIGNED_GET_EXPIRY_MINUTES", "15")),
            MaxFileSizeBytes = long.Parse(Optional("S3_MAX_FILE_SIZE_BYTES", "5242880")),
            RedisHost = Required("REDIS_HOST"),
            RedisPort = int.Parse(Required("REDIS_PORT")),
        };
    }

    static string Required(string key) =>
        NonEmpty(Environment.GetEnvironmentVariable(key)) ?? throw new InvalidOperationException($"{key} is required for cowork-user configuration");

    static string Optional(string key, string fallback) =>
        NonEmpty(Environment.GetEnvironmentVariable(key)) ?? fallback;

    static string? NonEmpty(string? value) => string.IsNullOrEmpty(value) ? null : value;

    static void ValidateStorage(AppConfig config)
    {
        foreach (var (endpoint, isPublic) in new[] { (config.S3InternalEndpoint, false), (config.PublicApiBaseUrl, true) })
        {
            if (!Uri.TryCreate(endpoint, UriKind.Absolute, out var uri)
                || uri.Scheme is not ("http" or "https") || uri.Host == ""
                || uri.AbsolutePath != "/" || uri.UserInfo != "" || uri.Query != "" || uri.Fragment != "")
                throw new InvalidOperationException("Storage and API endpoints must be an HTTP(S) origin");

            if (isPublic && config.ConfigProfile == "prod"
                && (uri.Scheme != "https" || uri.Host is "localhost" or "127.0.0.1" or "[::1]"))
                throw new InvalidOperationException("Production API endpoint requires HTTPS and a non-local host");
        }
    }

    static string DatabaseConnectionString()
    {
        return new MySqlConnectionStringBuilder
        {
            Server = Required("DB_HOST"),
            Port = PositiveInteger(Required("DB_PORT"), "DB_PORT", 65_535),
            Database = Required("DB_NAME"),
            UserID = Required("DB_USERNAME"),
            Password = Required("DB_PASSWORD"),
            MaximumPoolSize = PositiveInteger(Optional("DB_POOL_SIZE", "10"), "DB_POOL_SIZE"),
            CharacterSet = "utf8mb4",
            // CHAR(36) columns such as operation_id hold UUID text; the default would hand them back as Guid.
            GuidFormat = MySqlGuidFormat.None,
            DefaultCommandTimeout = 15,
            SslMode = MySqlSslMode.Preferred,
        }.ConnectionString;
    }

    static uint PositiveInteger(string value, string key, uint? maximum = null)
    {
        if (!uint.TryParse(value, out var parsed) || parsed == 0)
            throw new InvalidOperationException($"{key} must be a positive integer");
        if (maximum is { } max && parsed > max)
            throw new InvalidOperationException($"{key} must not exceed {max}");
        return parsed;
    }
}
