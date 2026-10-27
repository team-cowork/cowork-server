using MySqlConnector;

namespace CoworkUser.Config;

public sealed record AppConfig
{
    public required int Port { get; init; }
    public required KafkaSecurity KafkaSecurity { get; init; }
    public required string ConnectionString { get; init; }
    public required string ConfigProfile { get; init; }
    public required string KafkaBootstrapServers { get; init; }
    public required string KafkaTopic { get; init; }
    public required string KafkaGroupId { get; init; }
    public required string KafkaIdentityCommandTopic { get; init; }
    public required string KafkaIdentityCommandGroupId { get; init; }
    public required string KafkaIdentityCommandResultTopic { get; init; }
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
    public required IReadOnlyList<string> AllowedContentTypes { get; init; }
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
        string Lookup(string fallback, params string[] keys) =>
            keys.Select(Environment.GetEnvironmentVariable).FirstOrDefault(v => !string.IsNullOrEmpty(v))
            ?? fallback;

        var port = int.Parse(Lookup("8082", "PORT", "SERVER_PORT", "server_port"));

        if (Lookup("true", "KAFKA_ENABLED") != "true")
            throw new InvalidOperationException("KAFKA_ENABLED=false is unsupported because owner commands and projections require Kafka");

        return new AppConfig
        {
            Port = port,
            KafkaSecurity = CoworkUser.Config.KafkaSecurity.Load(),
            ConnectionString = DatabaseConnectionString(Lookup),
            ConfigProfile = Profile,
            KafkaBootstrapServers = Lookup("localhost:9094", "KAFKA_BOOTSTRAP_SERVERS", "kafka_bootstrap_servers"),
            KafkaTopic = Lookup("user.data.sync", "KAFKA_TOPIC_USER_SYNC", "kafka_topic"),
            KafkaGroupId = Lookup("cowork-user", "KAFKA_GROUP_ID", "kafka_group_id"),
            KafkaIdentityCommandTopic = Lookup("user.identity.command", "KAFKA_TOPIC_USER_IDENTITY_COMMAND", "kafka_identity_command_topic"),
            KafkaIdentityCommandGroupId = Lookup("cowork-user.user-identity-command", "KAFKA_GROUP_ID_USER_IDENTITY_COMMAND", "kafka_identity_command_group_id"),
            KafkaIdentityCommandResultTopic = Lookup("user.identity.command-result", "KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT", "kafka_identity_command_result_topic"),
            KafkaTeamMemberTopic = Lookup("team.member.event", "KAFKA_TOPIC_TEAM_MEMBER", "kafka_team_member_topic"),
            KafkaTeamMemberGroupId = Lookup("cowork-user.team-member", "KAFKA_GROUP_ID_TEAM_MEMBER", "kafka_team_member_group_id"),
            KafkaPresenceTopic = Lookup("user.presence.event", "KAFKA_TOPIC_USER_PRESENCE", "kafka_presence_topic"),
            KafkaPresenceGroupId = Lookup("cowork-user.user-presence", "KAFKA_GROUP_ID_USER_PRESENCE", "kafka_presence_group_id"),
            KafkaProfileTopic = Lookup("user.profile.event.v2", "KAFKA_TOPIC_USER_PROFILE", "kafka_profile_topic"),
            KafkaProfileSnapshotIntervalMs = int.Parse(Lookup("300000", "KAFKA_PROFILE_SNAPSHOT_INTERVAL_MS", "kafka_profile_snapshot_interval_ms")),
            S3Region = Lookup("ap-northeast-2", "S3_REGION", "s3_region"),
            S3InternalEndpoint = Lookup("http://localhost:9000", "S3_INTERNAL_ENDPOINT", "s3_internal_endpoint"),
            PublicApiBaseUrl = Lookup("http://localhost:8080", "PUBLIC_API_BASE_URL", "public_api_base_url"),
            S3AccessKey = Lookup("", "S3_ACCESS_KEY", "s3_access_key"),
            S3SecretKey = Lookup("", "S3_SECRET_KEY", "s3_secret_key"),
            S3Bucket = Lookup("cowork-bucket", "S3_BUCKET", "s3_bucket"),
            S3PathStyle = Lookup("true", "S3_PATH_STYLE_ACCESS_ENABLED", "s3_path_style_access_enabled") == "true",
            PresignedPutExpiryMinutes = int.Parse(Lookup("5", "S3_PRESIGNED_PUT_EXPIRY_MINUTES", "s3_presigned_put_expiry_minutes")),
            PresignedGetExpiryMinutes = int.Parse(Lookup("15", "S3_PRESIGNED_GET_EXPIRY_MINUTES", "s3_presigned_get_expiry_minutes")),
            MaxFileSizeBytes = long.Parse(Lookup("5242880", "S3_MAX_FILE_SIZE_BYTES", "s3_max_file_size_bytes")),
            AllowedContentTypes = ParseCsvOrList(Lookup("image/jpeg,image/png,image/webp", "S3_ALLOWED_CONTENT_TYPES")),
            RedisHost = Lookup("localhost", "REDIS_HOST", "redis_host"),
            RedisPort = int.Parse(Lookup("6379", "REDIS_PORT", "redis_port")),
        };
    }

    static string Profile => NonEmpty(Environment.GetEnvironmentVariable("APP_CONFIG_PROFILE")) ?? "local";

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

        if (config.S3AccessKey == "" || config.S3SecretKey == "" || config.S3Bucket == "")
            throw new InvalidOperationException("S3 bucket and service credentials are required");
    }

    static string DatabaseConnectionString(Func<string, string[], string> lookup)
    {
        string Required(string key) =>
            NonEmpty(lookup("", [key, key.ToLowerInvariant()])) ?? throw new InvalidOperationException($"{key} is required for cowork-user database configuration");

        return new MySqlConnectionStringBuilder
        {
            Server = Required("DB_HOST"),
            Port = PositiveInteger(Required("DB_PORT"), "DB_PORT", 65_535),
            Database = Required("DB_NAME"),
            UserID = Required("DB_USERNAME"),
            Password = Required("DB_PASSWORD"),
            MaximumPoolSize = PositiveInteger(lookup("10", ["DB_POOL_SIZE", "db_pool_size"]), "DB_POOL_SIZE"),
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

    internal static List<string> ParseCsvOrList(string value) =>
        value.Trim().TrimStart('[').TrimEnd(']')
            .Split(',', StringSplitOptions.RemoveEmptyEntries)
            .Select(entry => entry.Trim().Trim('"').Trim('\''))
            .Where(entry => entry != "")
            .ToList();
}
