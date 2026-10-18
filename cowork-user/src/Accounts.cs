using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using CoworkUser.Kafka;
using Dapper;
using MySqlConnector;
using StackExchange.Redis;

namespace CoworkUser;

public sealed record UserResponse(
    long Id, string Name, string Email, string Sex, string? GithubId, string? AccountDescription,
    string? StudentRole, string? StudentNumber, string? Major, string? Specialty, string Status,
    string? CustomStatus, string? StatusMessage, DateTime? StatusExpiresAt, string? ProfileImageUrl,
    string? Nickname, IReadOnlyList<string> Roles, string? Description);

public sealed record DisplayName(long Id, string Name, string? Nickname);

public sealed record SearchResult(IReadOnlyList<UserResponse> Items, int Page, int PageSize, long TotalCount, bool HasNext);

public sealed record PresignedUpload(string UploadUrl, string ObjectKey);

/// <summary>A changeset-style validation failure, formatted as "field message; field message".</summary>
public sealed class FieldErrors : Exception
{
    readonly SortedDictionary<string, List<string>> errors = new(StringComparer.Ordinal);

    public void Add(string field, string message)
    {
        if (!errors.TryGetValue(field, out var messages)) errors[field] = messages = [];
        messages.Add(message);
    }

    public bool Any => errors.Count > 0;

    public override string Message =>
        string.Join("; ", errors.Select(error => $"{error.Key} {string.Join(", ", error.Value)}"));

    public void ThrowIfAny()
    {
        if (Any) throw this;
    }
}

public static partial class Accounts
{
    public const int CustomStatusMaxLength = 30;
    public const int StatusMessageMaxLength = 100;
    const int DisplayNameCacheTtlSeconds = 60;
    const int DisplayNameNotFoundTtlSeconds = 30;
    const string NotFoundMarker = "__not_found__";
    const int MaxRawCsvTokens = 1000;

    public static IConnectionMultiplexer? Redis { get; set; }

    sealed class ProfileRow
    {
        public long Id { get; init; }
        public string Name { get; init; } = "";
        public string Email { get; init; } = "";
        public string Sex { get; init; } = "";
        public string? Github { get; init; }
        public string? AccountDescription { get; init; }
        public string? StudentRole { get; init; }
        public string? StudentNumber { get; init; }
        public string? Major { get; init; }
        public string? Specialty { get; init; }
        public string Status { get; init; } = "";
        public string? CustomStatus { get; init; }
        public string? StatusMessage { get; init; }
        public DateTime? StatusExpiresAt { get; init; }
        public long ProfileId { get; init; }
        public string? ProfileImageKey { get; init; }
        public string? Nickname { get; init; }
        public string? Description { get; init; }
    }

    const string ProfileSelect = """
        SELECT a.id, a.name, a.email, a.sex, a.github, a.description AS account_description,
               a.student_role, a.student_number, a.major, a.specialty, a.status, a.custom_status,
               a.status_message, a.status_expires_at, p.id AS profile_id, p.profile_image_key,
               p.nickname, p.description
        FROM tb_profiles p
        JOIN tb_accounts a ON a.id = p.account_id
        """;

    public static async Task<UserResponse> GetUserProfileAsync(long userId)
    {
        await using var connection = await App.OpenAsync();
        var rows = await connection.QueryAsync<ProfileRow>($"{ProfileSelect} WHERE p.account_id = @userId", new { userId });
        return (await ToUserResponsesAsync(connection, rows.ToList())).SingleOrDefault() ?? throw ApiError.NotFound();
    }

    /// <summary>Looks up a cowork user by GitHub login (tb_accounts.github, unique).</summary>
    public static async Task<UserResponse> GetByGithubAsync(string username)
    {
        await using var connection = await App.OpenAsync();
        var id = await connection.QuerySingleOrDefaultAsync<long?>("SELECT id FROM tb_accounts WHERE github = @username", new { username });
        return id is null ? throw ApiError.NotFound() : await GetUserProfileAsync(id.Value);
    }

    static async Task<List<UserResponse>> ToUserResponsesAsync(MySqlConnection connection, List<ProfileRow> rows)
    {
        if (rows.Count == 0) return [];
        var roles = (await connection.QueryAsync<(long ProfileId, string Role)>(
                "SELECT profile_id, role FROM tb_profile_roles WHERE profile_id IN @ids", new { ids = rows.Select(r => r.ProfileId) }))
            .ToLookup(r => r.ProfileId, r => r.Role);

        return rows.Select(row => new UserResponse(
            row.Id, row.Name, row.Email, row.Sex, row.Github, row.AccountDescription, row.StudentRole,
            row.StudentNumber, row.Major, row.Specialty, row.Status, row.CustomStatus, row.StatusMessage,
            // status_expires_at is exposed with second precision.
            row.StatusExpiresAt is { } expires ? DateTime.SpecifyKind(expires.AddTicks(-(expires.Ticks % TimeSpan.TicksPerSecond)), DateTimeKind.Utc) : null,
            row.ProfileImageKey is { } key ? ObjectStorage.PresignedGetUrl(key) : null,
            row.Nickname,
            roles[row.ProfileId].Distinct().Order(StringComparer.Ordinal).ToList(),
            row.Description ?? row.AccountDescription)).ToList();
    }

    /// <summary>
    /// Batch lookup of display names. Names are cached in Redis for 60s and unknown IDs for 30s
    /// (negative caching); a Redis failure falls back to the database.
    /// </summary>
    public static async Task<List<DisplayName>> GetDisplayNamesAsync(IReadOnlyList<long> ids)
    {
        var cached = await FetchCachedDisplayNamesAsync(ids);
        var missing = ids.Where(id => !cached.ContainsKey(id)).ToList();

        var fresh = new List<DisplayName>();
        if (missing.Count > 0)
        {
            await using var connection = await App.OpenAsync();
            fresh = (await connection.QueryAsync<DisplayName>(
                "SELECT a.id, a.name, p.nickname FROM tb_profiles p JOIN tb_accounts a ON a.id = p.account_id WHERE a.id IN @missing",
                new { missing })).ToList();
        }

        var found = fresh.Select(d => d.Id).ToHashSet();
        await PersistCacheAsync(
            fresh.Select(d => (d.Id, JsonSerializer.Serialize(new { name = d.Name, nickname = d.Nickname }, Json.Event), DisplayNameCacheTtlSeconds))
                .Concat(missing.Where(id => !found.Contains(id)).Select(id => (id, NotFoundMarker, DisplayNameNotFoundTtlSeconds))));

        return cached.Values.OfType<DisplayName>().Concat(fresh).ToList();
    }

    static string DisplayNameCacheKey(long id) => $"user:display_name:{id}";

    static async Task<Dictionary<long, DisplayName?>> FetchCachedDisplayNamesAsync(IReadOnlyList<long> ids)
    {
        var result = new Dictionary<long, DisplayName?>();
        if (ids.Count == 0 || Redis is null) return result;
        RedisValue[] values;
        try
        {
            values = await Redis.GetDatabase().StringGetAsync(ids.Select(id => (RedisKey)DisplayNameCacheKey(id)).ToArray());
        }
        catch (Exception)
        {
            return result;
        }

        foreach (var (id, value) in ids.Zip(values))
        {
            if (value.IsNull) continue;
            if (value == NotFoundMarker)
            {
                result[id] = null;
                continue;
            }
            try
            {
                var payload = JsonDocument.Parse((string)value!).RootElement;
                if (payload.Get("name") is { } name && payload.Get("nickname") is { } nickname)
                    result[id] = new DisplayName(id, name.GetString()!, nickname.ValueKind == JsonValueKind.Null ? null : nickname.GetString());
            }
            catch (Exception)
            {
                // An unreadable entry is a cache miss.
            }
        }
        return result;
    }

    static async Task PersistCacheAsync(IEnumerable<(long Id, string Value, int Ttl)> entries)
    {
        var list = entries.ToList();
        if (list.Count == 0 || Redis is null) return;
        try
        {
            var batch = Redis.GetDatabase().CreateBatch();
            var writes = list.Select(e => batch.StringSetAsync(DisplayNameCacheKey(e.Id), e.Value, TimeSpan.FromSeconds(e.Ttl))).ToList();
            batch.Execute();
            await Task.WhenAll(writes);
        }
        catch (Exception exception)
        {
            App.Logs.CreateLogger("CoworkUser.Accounts").LogWarning("Failed to store display name cache: {Reason}", exception.Message);
        }
    }

    internal sealed record ProfilePatch(
        IReadOnlyDictionary<string, JsonElement> Profile,
        IReadOnlyDictionary<string, JsonElement> Account,
        IReadOnlyList<string>? Roles);

    internal static ProfilePatch ParseProfilePatch(JsonElement attrs)
    {
        Dictionary<string, JsonElement> Take(params (string Key, string Field)[] fields) =>
            fields.Where(f => attrs.TryGetProperty(f.Key, out _)).ToDictionary(f => f.Field, f => attrs.GetProperty(f.Key));

        IReadOnlyList<string>? roles = null;
        if (attrs.TryGetProperty("roles", out var rawRoles))
        {
            if (rawRoles.ValueKind != JsonValueKind.Array) throw ApiError.Validation("roles는 배열이어야 합니다.");
            if (rawRoles.EnumerateArray().Any(r => r.ValueKind != JsonValueKind.String))
                throw ApiError.Validation("roles의 각 값은 문자열이어야 합니다.");
            roles = rawRoles.EnumerateArray().Select(r => r.GetString()!.Trim()).Where(r => r != "")
                .Distinct().Order(StringComparer.Ordinal).ToList();
        }

        return new ProfilePatch(Take(("nickname", "nickname"), ("description", "description")),
            Take(("name", "name"), ("github_id", "github")), roles);
    }

    /// <summary>Casts a JSON value into a nullable string column; whitespace-only text is empty.</summary>
    internal static bool TryCastString(JsonElement value, out string? result)
    {
        result = null;
        if (value.ValueKind == JsonValueKind.Null) return true;
        if (value.ValueKind != JsonValueKind.String) return false;
        var text = value.GetString()!;
        result = text.Trim() == "" ? null : text;
        return true;
    }

    static Dictionary<string, string?> CastStrings(IReadOnlyDictionary<string, JsonElement> fields, FieldErrors errors)
    {
        var cast = new Dictionary<string, string?>();
        foreach (var (field, value) in fields)
        {
            if (TryCastString(value, out var text)) cast[field] = text;
            else errors.Add(field, "is invalid");
        }
        return cast;
    }

    public static async Task<UserResponse> UpdateMyProfileAsync(long userId, JsonElement attrs)
    {
        var patch = ParseProfilePatch(attrs);

        await App.TransactionAsync<int>(async (connection, transaction) =>
        {
            var account = await connection.QueryAsync<(string Name, string? Github)>(
                "SELECT name, github FROM tb_accounts WHERE id = @userId FOR UPDATE", new { userId }, transaction).FirstOrNullAsync() ?? throw ApiError.NotFound();
            var profile = await connection.QueryAsync<(long Id, string? Nickname, string? Description)>(
                "SELECT id, nickname, description FROM tb_profiles WHERE account_id = @userId FOR UPDATE", new { userId }, transaction).FirstOrNullAsync() ?? throw ApiError.NotFound();

            var profileErrors = new FieldErrors();
            var profileChanges = CastStrings(patch.Profile, profileErrors);
            profileErrors.ThrowIfAny();
            var profileChanged = (profileChanges.TryGetValue("nickname", out var nickname) && nickname != profile.Nickname)
                                 | (profileChanges.TryGetValue("description", out var description) && description != profile.Description);
            if (profileChanged)
            {
                await connection.ExecuteAsync("""
                    UPDATE tb_profiles
                    SET nickname = @nickname, description = @description, last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = @id
                    """, new
                {
                    nickname = profileChanges.GetValueOrDefault("nickname", profile.Nickname),
                    description = profileChanges.GetValueOrDefault("description", profile.Description),
                    userId,
                    id = profile.Id,
                }, transaction);
            }

            var rolesChanged = false;
            if (patch.Roles is { } roles)
            {
                var current = (await connection.QueryAsync<string>(
                        "SELECT role FROM tb_profile_roles WHERE profile_id = @id", new { id = profile.Id }, transaction))
                    .Distinct().Order(StringComparer.Ordinal).ToList();
                if (!current.SequenceEqual(roles))
                {
                    await connection.ExecuteAsync("DELETE FROM tb_profile_roles WHERE profile_id = @id", new { id = profile.Id }, transaction);
                    await connection.ExecuteAsync("INSERT INTO tb_profile_roles (profile_id, role) VALUES (@profileId, @role)",
                        roles.Select(role => new { profileId = profile.Id, role }), transaction);
                    rolesChanged = true;
                }
            }

            var accountErrors = new FieldErrors();
            var accountChanges = CastStrings(patch.Account, accountErrors);
            accountErrors.ThrowIfAny();
            var accountChanged = (accountChanges.TryGetValue("name", out var name) && name != account.Name)
                                 | (accountChanges.TryGetValue("github", out var github) && github != account.Github);
            if (accountChanged)
            {
                if (accountChanges.ContainsKey("name") && name is null)
                    throw ApiError.Validation("name can't be blank");
                try
                {
                    await connection.ExecuteAsync("""
                        UPDATE tb_accounts
                        SET name = @name, github = @github, last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6)
                        WHERE id = @userId
                        """, new
                    {
                        name = accountChanges.ContainsKey("name") ? name : account.Name,
                        github = accountChanges.ContainsKey("github") ? github : account.Github,
                        userId,
                    }, transaction);
                }
                catch (MySqlException error) when (error.ErrorCode == MySqlErrorCode.DuplicateKeyEntry && error.Message.Contains("uq_tb_accounts_github"))
                {
                    throw ApiError.Validation("github has already been taken");
                }
            }

            if (profileChanged || rolesChanged || accountChanged)
                await ProfileEventPublisher.EnqueueCurrentUpsertAsync(connection, transaction, userId);
            return 0;
        });

        return await GetUserProfileAsync(userId);
    }

    public static async Task<UserResponse> UpdateMyStatusAsync(long userId, JsonElement attrs)
    {
        if (attrs.ValueKind != JsonValueKind.Object || !attrs.TryGetProperty("custom_status", out var rawStatus))
            throw ApiError.Validation("custom_status 필드는 필수입니다.");

        await using var connection = await App.OpenAsync();
        var current = await connection.QueryAsync<(string? CustomStatus, string? StatusMessage, DateTime? StatusExpiresAt)>("""
            SELECT a.custom_status, a.status_message, a.status_expires_at
            FROM tb_profiles p JOIN tb_accounts a ON a.id = p.account_id
            WHERE p.account_id = @userId
            """, new { userId }).FirstOrNullAsync() ?? throw ApiError.NotFound();

        var change = CustomStatusChange(attrs, rawStatus, current.StatusMessage, current.StatusExpiresAt);
        await connection.ExecuteAsync("""
            UPDATE tb_accounts
            SET custom_status = @CustomStatus, status_message = @StatusMessage, status_expires_at = @StatusExpiresAt,
                last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6)
            WHERE id = @userId
            """, new { change.CustomStatus, change.StatusMessage, change.StatusExpiresAt, userId });

        return await GetUserProfileAsync(userId);
    }

    internal sealed record StatusChange(string CustomStatus, string? StatusMessage, DateTime? StatusExpiresAt);

    internal static StatusChange CustomStatusChange(JsonElement attrs, JsonElement rawStatus, string? currentMessage, DateTime? currentExpiresAt)
    {
        var errors = new FieldErrors();
        if (!TryCastString(rawStatus, out var customStatus)) errors.Add("custom_status", "is invalid");
        else if (customStatus is null) errors.Add("custom_status", "can't be blank");
        else if (TextLength(customStatus) > CustomStatusMaxLength) errors.Add("custom_status", $"should be at most {CustomStatusMaxLength} character(s)");

        var message = currentMessage;
        if (attrs.TryGetProperty("message", out var rawMessage))
        {
            if (!TryCastString(rawMessage, out message)) errors.Add("status_message", "is invalid");
            else if (message is not null && TextLength(message) > StatusMessageMaxLength)
                errors.Add("status_message", $"should be at most {StatusMessageMaxLength} character(s)");
        }

        var expiresAt = currentExpiresAt;
        if (attrs.TryGetProperty("expiresAt", out var rawExpires))
        {
            if (rawExpires.ValueKind == JsonValueKind.Null) expiresAt = null;
            else if (rawExpires.ValueKind == JsonValueKind.String && rawExpires.GetString()!.Trim() == "") expiresAt = null;
            else if (rawExpires.ValueKind == JsonValueKind.String && Iso8601.TryParse(rawExpires.GetString(), out var parsed, out _, assumeUtc: true))
                expiresAt = parsed.AddTicks(-(parsed.Ticks % TimeSpan.TicksPerSecond));
            else errors.Add("status_expires_at", "is invalid");
        }

        errors.ThrowIfAny();
        return new StatusChange(customStatus!, message, expiresAt);
    }

    /// <summary>Length in user-perceived characters (graphemes), as Ecto's validate_length counts.</summary>
    internal static int TextLength(string text) => new StringInfo(text).LengthInTextElements;

    static async Task<(long Id, string? ImageKey)> LoadProfileImageAsync(MySqlConnection connection, long userId) =>
        await connection.QueryAsync<(long, string?)>(
            "SELECT id, profile_image_key FROM tb_profiles WHERE account_id = @userId", new { userId }).FirstOrNullAsync() ?? throw ApiError.NotFound();

    public static async Task<PresignedUpload> GeneratePresignedUrlAsync(long userId, JsonElement attrs)
    {
        if (attrs.ValueKind != JsonValueKind.Object || !attrs.TryGetProperty("content_type", out var rawType))
            throw ApiError.Validation("content_type 값이 필요합니다.");

        await using (var connection = await App.OpenAsync()) await LoadProfileImageAsync(connection, userId);
        var contentType = rawType.ValueKind == JsonValueKind.String ? rawType.GetString() : null;
        if (!ObjectStorage.ContentTypeAllowed(contentType)) throw ApiError.Validation("허용되지 않는 파일 형식입니다.");

        var objectKey = ObjectStorage.BuildObjectKey(userId, contentType!);
        return new PresignedUpload(ObjectStorage.PresignedPutUrl(objectKey, contentType!), objectKey);
    }

    public static async Task ConfirmUploadAsync(long userId, JsonElement attrs)
    {
        if (attrs.ValueKind != JsonValueKind.Object || !attrs.TryGetProperty("object_key", out var rawKey))
            throw ApiError.Validation("object_key 값이 필요합니다.");

        (long Id, string? ImageKey) profile;
        await using (var connection = await App.OpenAsync()) profile = await LoadProfileImageAsync(connection, userId);

        // The connection is released while S3 is called, so slow storage cannot exhaust the pool.
        var objectKey = rawKey.ValueKind == JsonValueKind.String ? rawKey.GetString() : null;
        var finalKey = await ObjectStorage.VerifyUploadAsync(userId, objectKey);
        await using (var connection = await App.OpenAsync())
            await connection.ExecuteAsync(
                "UPDATE tb_profiles SET profile_image_key = @finalKey, last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6) WHERE id = @id",
                new { finalKey, userId, id = profile.Id });

        if (profile.ImageKey is not null && profile.ImageKey != finalKey) await ObjectStorage.DeleteObjectAsync(profile.ImageKey);
        await ObjectStorage.DeleteObjectAsync(objectKey);
    }

    public static async Task DeleteProfileImageAsync(long userId)
    {
        await using var connection = await App.OpenAsync();
        var profile = await LoadProfileImageAsync(connection, userId);
        if (profile.ImageKey is null) return;
        await connection.ExecuteAsync(
            "UPDATE tb_profiles SET profile_image_key = NULL, last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6) WHERE id = @id",
            new { userId, id = profile.Id });
        await ObjectStorage.DeleteObjectAsync(profile.ImageKey);
    }

    /// <summary>
    /// Applies an authorization login command to the account and profile inside the caller's
    /// transaction. Returns false when a unique constraint rejects the identity.
    /// </summary>
    public static async Task<bool> ApplyIdentityCommandAsync(MySqlConnection connection, MySqlTransaction transaction, IdentityCommandContract.Command command)
    {
        var existing = await connection.QuerySingleOrDefaultAsync<long?>(
            "SELECT id FROM tb_accounts WHERE id = @UserId FOR UPDATE", new { command.UserId }, transaction);

        var parameters = new
        {
            command.UserId,
            command.Name,
            command.Email,
            command.Sex,
            github = NullIfBlank(command.GithubId),
            command.Role,
            studentNumber = StudentNumber(command.Grade, command.ClassNumber, command.StudentNumberInClass),
            command.DatagsmStudentId,
            command.Major,
            command.RequestedBy,
        };

        try
        {
            if (existing is null)
            {
                await connection.ExecuteAsync("""
                    INSERT INTO tb_accounts
                        (id, name, email, sex, github, student_role, student_number, datagsm_student_id, major,
                         status, presence_updated_at, created_by, last_modified_by, created_at, updated_at)
                    VALUES (@UserId, @Name, @Email, @Sex, @github, @Role, @studentNumber, @DatagsmStudentId, @Major,
                            'offline', '1970-01-01 00:00:00.000000', @RequestedBy, @RequestedBy, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, parameters, transaction);
            }
            else
            {
                await connection.ExecuteAsync("""
                    UPDATE tb_accounts
                    SET name = @Name, email = @Email, sex = @Sex, github = @github, student_role = @Role,
                        student_number = @studentNumber, datagsm_student_id = @DatagsmStudentId, major = @Major,
                        last_modified_by = @RequestedBy, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = @UserId
                    """, parameters, transaction);
            }
        }
        catch (MySqlException error) when (error.ErrorCode == MySqlErrorCode.DuplicateKeyEntry)
        {
            return false;
        }

        var profileId = await connection.QuerySingleOrDefaultAsync<long?>(
            "SELECT id FROM tb_profiles WHERE account_id = @UserId FOR UPDATE", new { command.UserId }, transaction);
        if (profileId is null)
        {
            await connection.ExecuteAsync(
                "INSERT INTO tb_profiles (account_id, created_at, updated_at) VALUES (@UserId, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                new { command.UserId }, transaction);
        }

        await PresenceProjection.ApplyLatestToAccountAsync(connection, transaction, command.UserId);
        await ProfileEventPublisher.EnqueueCurrentUpsertAsync(connection, transaction, command.UserId);
        return true;
    }

    static string? NullIfBlank(string? value) => value is null || value.Trim() == "" ? null : value;

    internal static string? StudentNumber(long? grade, long? classNumber, long? number) =>
        grade is { } g && classNumber is { } c && number is { } n ? $"{g}{c}{n.ToString(CultureInfo.InvariantCulture).PadLeft(2, '0')}" : null;

    public enum StudentEventResult { Updated, AccountNotFound, Stale }

    /// <summary>
    /// Applies a DataGSM student.updated event, matched by the immutable datagsm_student_id. Users who
    /// have not joined cowork are skipped. Throws <see cref="FieldErrors"/> for rejected data.
    /// </summary>
    public static async Task<StudentEventResult> ApplyStudentEventAsync(JsonElement payload)
    {
        // UserSyncContract has already validated the payload shape.
        var studentId = payload.Get("datagsm_student_id").Integer();
        var studentRole = payload.GetString("student_role");
        Iso8601.TryParse(payload.GetString("occurred_at"), out var occurredAt, out _);

        return await App.TransactionAsync(async (connection, transaction) =>
        {
            var account = await connection.QueryAsync<(long Id, DateTime? DatagsmUpdatedAt)>(
                "SELECT id, datagsm_updated_at FROM tb_accounts WHERE datagsm_student_id = @studentId FOR UPDATE",
                new { studentId }, transaction).FirstOrNullAsync();
            if (account is null) return StudentEventResult.AccountNotFound;
            if (!StudentEventNewer(account.Value.DatagsmUpdatedAt is { } current ? Iso8601.AsUtc(current) : null, occurredAt))
                return StudentEventResult.Stale;

            string? Field(string key) => NullIfBlank(payload.GetString(key));
            var studentNumber = payload.Get("student_number") switch
            {
                null or { ValueKind: JsonValueKind.Null } => null,
                { ValueKind: JsonValueKind.String } text => NullIfBlank(text.GetString()),
                var other => other.Value.GetRawText(),
            };

            try
            {
                await connection.ExecuteAsync("""
                    UPDATE tb_accounts
                    SET name = @name, email = @email, sex = @sex, github = @github, student_role = @studentRole,
                        student_number = @studentNumber, major = @major, specialty = @specialty,
                        datagsm_updated_at = @occurredAt, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = @id
                    """, new
                {
                    name = Field("name"),
                    email = Field("email"),
                    sex = Field("sex"),
                    github = Field("github_id"),
                    studentRole,
                    studentNumber,
                    major = Field("major"),
                    specialty = Field("specialty"),
                    occurredAt,
                    id = account.Value.Id,
                }, transaction);
            }
            catch (MySqlException error) when (error.ErrorCode == MySqlErrorCode.DuplicateKeyEntry)
            {
                var errors = new FieldErrors();
                errors.Add(error.Message.Contains("uq_tb_accounts_github") ? "github" : "email", "has already been taken");
                throw errors;
            }

            await ProfileEventPublisher.EnqueueCurrentUpsertAsync(connection, transaction, account.Value.Id);
            return StudentEventResult.Updated;
        });
    }

    internal static bool StudentEventNewer(DateTime? current, DateTime incoming) => current is null || incoming > current;

    public static async Task<SearchResult> SearchUsersAsync(long requesterUserId, IReadOnlyDictionary<string, string> query)
    {
        ValidateSearchFilters(query);
        var userIds = await AuthorizeTeamSearchAsync(requesterUserId, query, TeamMembershipProjection.MemberIdsForRequesterAsync);

        var page = ParsePositiveInt(query.GetValueOrDefault("page"), 1);
        var pageSize = Math.Min(ParsePositiveInt(query.GetValueOrDefault("page_size"), 20), 100);

        var (where, parameters) = SearchFilter(query, userIds);
        await using var connection = await App.OpenAsync();
        var totalCount = await connection.ExecuteScalarAsync<long>(
            $"SELECT COUNT(DISTINCT p.id) FROM tb_profiles p JOIN tb_accounts a ON a.id = p.account_id {where}", parameters);

        parameters.Add("limit", pageSize + 1);
        parameters.Add("offset", (long)(page - 1) * pageSize);
        var rows = (await connection.QueryAsync<ProfileRow>(
            $"{ProfileSelect} {where} ORDER BY {SortClause(query.GetValueOrDefault("sort_by", "id"), query.GetValueOrDefault("sort_order", "asc"))} LIMIT @limit OFFSET @offset",
            parameters)).ToList();

        var items = await ToUserResponsesAsync(connection, rows.Take(pageSize).ToList());
        return new SearchResult(items, page, pageSize, totalCount, rows.Count > pageSize);
    }

    internal static void ValidateSearchFilters(IReadOnlyDictionary<string, string> query)
    {
        if (query.TryGetValue("status", out var status) && status is not ("online" or "offline"))
            throw ApiError.Validation("status 값은 online 또는 offline이어야 합니다.");
        if (query.TryGetValue("custom_status", out var customStatus) && TextLength(customStatus) > CustomStatusMaxLength)
            throw ApiError.Validation($"custom_status 값은 {CustomStatusMaxLength}자 이하여야 합니다.");
    }

    /// <summary>Restricts a teamId search to the active members visible to the requester.</summary>
    internal static async Task<IReadOnlyList<long>?> AuthorizeTeamSearchAsync(
        long requesterUserId, IReadOnlyDictionary<string, string> query, Func<long, long, Task<IReadOnlyList<long>>> memberIds)
    {
        if (!query.TryGetValue("teamId", out var rawTeamId)) return null;
        if (!long.TryParse(rawTeamId, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var teamId) || teamId <= 0)
            throw ApiError.Validation("teamId 값이 올바르지 않습니다.");
        if (requesterUserId <= 0) throw ApiError.Validation("X-User-Id 값이 올바르지 않습니다.");

        IReadOnlyList<long> ids;
        try
        {
            ids = await memberIds(teamId, requesterUserId);
        }
        catch (Exception exception) when (exception is not ApiError)
        {
            throw new ApiError(503, "팀 멤버 투영을 조회할 수 없습니다.");
        }
        return ids.Count == 0 ? throw new ApiError(403, "해당 팀의 멤버만 사용자를 검색할 수 있습니다.") : ids;
    }

    static (string Where, DynamicParameters Parameters) SearchFilter(IReadOnlyDictionary<string, string> query, IReadOnlyList<long>? teamMemberIds)
    {
        var clauses = new List<string>();
        var parameters = new DynamicParameters();
        var joins = new StringBuilder();

        void Like(string column, string key)
        {
            if (NormalizeSearchTerm(query.GetValueOrDefault(key)) is not { } term) return;
            clauses.Add($"{column} LIKE @{key}");
            parameters.Add(key, LikePattern(term));
        }

        void Equal(string column, string key)
        {
            if (query.GetValueOrDefault(key) is not { Length: > 0 } value) return;
            clauses.Add($"{column} = @{key}");
            parameters.Add(key, value);
        }

        Like("a.name", "name");
        Like("p.nickname", "nickname");
        Equal("a.major", "major");
        Equal("a.student_role", "student_role");
        Equal("a.status", "status");
        Equal("a.custom_status", "custom_status");
        if (query.GetValueOrDefault("role") is { Length: > 0 } role)
        {
            joins.Append(" JOIN tb_profile_roles pr ON pr.profile_id = p.id AND pr.role = @role");
            parameters.Add("role", role);
        }
        if (SearchTerm(query.GetValueOrDefault("q"), query.GetValueOrDefault("query")) is { } q)
        {
            clauses.Add("(a.name LIKE @q OR p.nickname LIKE @q)");
            parameters.Add("q", LikePattern(q));
        }

        var userIds = teamMemberIds ?? (query.TryGetValue("user_ids", out var csv) ? ParseIntCsv(csv) : null);
        if (userIds is not null)
        {
            clauses.Add(userIds.Count == 0 ? "FALSE" : "a.id IN @userIds");
            parameters.Add("userIds", userIds);
        }

        return ($"{joins}{(clauses.Count == 0 ? "" : " WHERE " + string.Join(" AND ", clauses))}", parameters);
    }

    internal static string? NormalizeSearchTerm(string? value) => value?.Trim() is { Length: > 0 } term ? term : null;

    /// <summary>`query` is a compatibility alias of `q`, used when q is absent or blank.</summary>
    internal static string? SearchTerm(string? q, string? query) => NormalizeSearchTerm(q) ?? NormalizeSearchTerm(query);

    // %, _ and \ are escaped with MySQL LIKE's default escape character. Case sensitivity follows
    // the utf8mb4_unicode_ci column collation.
    internal static string LikePattern(string term) => "%" + LikeEscape().Replace(term, @"\$0") + "%";

    [GeneratedRegex(@"[%_\\]")]
    private static partial Regex LikeEscape();

    static string SortClause(string sortBy, string sortOrder)
    {
        var direction = sortOrder == "desc" ? "DESC" : "ASC";
        return sortBy switch
        {
            "name" => $"a.name {direction}",
            "nickname" => $"p.nickname {direction}",
            "major" => $"a.major {direction}",
            _ => $"p.account_id {direction}",
        };
    }

    /// <summary>
    /// Parses comma-separated positive integer IDs, ignoring invalid tokens. Tokens beyond the first
    /// 1000 are not parsed, so repeated tokens cannot make the split and parse unbounded.
    /// </summary>
    public static List<long> ParseIntCsv(string ids) =>
        ids.Split(',', MaxRawCsvTokens + 1)
            .Select(token => long.TryParse(token.Trim(), NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var id) ? id : 0)
            .Where(id => id > 0)
            .ToList();

    internal static int ParsePositiveInt(string? value, int fallback) =>
        int.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var parsed) && parsed > 0 ? parsed : fallback;
}
