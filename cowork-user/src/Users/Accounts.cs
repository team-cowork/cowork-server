using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using CoworkUser.Http;
using CoworkUser.Kafka.Commands;
using CoworkUser.Kafka.Projections;
using CoworkUser.Kafka.Publishing;
using CoworkUser.Platform;
using Dapper;
using MySqlConnector;
using StackExchange.Redis;

namespace CoworkUser.Users;

public static partial class Accounts
{
    public const int CustomStatusMaxLength = 30;
    public const int StatusMessageMaxLength = 100;
    public const int NameMaxLength = 50;
    public const int NicknameMaxLength = 50;
    public const int DescriptionMaxLength = 500;
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
        public string? Sex { get; init; }
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
        public string? BannerColor { get; init; }
    }

    const string ProfileFrom = """
        FROM tb_profiles p
        JOIN tb_accounts a ON a.id = p.account_id
        LEFT JOIN tb_gsm_student_info s ON s.account_id = a.id
        """;

    const string ProfileSelect = """
        SELECT a.id, a.name, a.email, s.sex, a.github, a.description AS account_description,
               s.student_role, s.student_number, s.major, s.specialty, a.status, a.custom_status,
               a.status_message, a.status_expires_at, p.id AS profile_id, p.profile_image_key,
               p.nickname, p.description, p.banner_color
        """ + " " + ProfileFrom;

    public static async Task<UserResponse> GetUserProfileAsync(long userId)
    {
        await using var connection = await App.OpenAsync();
        return await GetUserProfileAsync(connection, userId);
    }

    // Callers that already hold a connection pass it in; opening a second one while holding the first can exhaust the pool.
    static async Task<UserResponse> GetUserProfileAsync(MySqlConnection connection, long userId)
    {
        var rows = await connection.QueryAsync<ProfileRow>($"{ProfileSelect} WHERE p.account_id = @userId", new { userId });
        return (await ToUserResponsesAsync(connection, rows.ToList())).SingleOrDefault() ?? throw ApiError.NotFound();
    }

    /// <summary>Looks up a cowork user by GitHub login (tb_accounts.github, unique).</summary>
    public static async Task<UserResponse> GetByGithubAsync(string username)
    {
        await using var connection = await App.OpenAsync();
        var id = await connection.QuerySingleOrDefaultAsync<long?>("SELECT id FROM tb_accounts WHERE github = @username", new { username });
        return id is null ? throw ApiError.NotFound() : await GetUserProfileAsync(connection, id.Value);
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
            row.Description ?? row.AccountDescription,
            row.BannerColor)).ToList();
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
            fresh.Select(d => (d.Id, Json.Encode(new { name = d.Name, nickname = d.Nickname }), DisplayNameCacheTtlSeconds))
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
        IReadOnlyList<string>? Roles,
        JsonElement? Github,
        JsonElement? BannerColor
        );

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
            Take(("name", "name")), roles, attrs.TryGetProperty("github_id", out var github) ? github : null,
            attrs.TryGetProperty("banner_color", out var bannerColor) ? bannerColor : null);
    }

    /// <summary>Preset keys for the profile banner. Clients map each key to a color for their theme.</summary>
    internal static readonly string[] BannerColors = ["RED", "BLUE", "GREEN", "AMBER", "PURPLE", "NEUTRAL"];

    /// <summary>Casts a banner color: null resets it to the client default, any other value must be a preset key.</summary>
    internal static string? CastBannerColor(JsonElement value, FieldErrors errors)
    {
        if (value.ValueKind == JsonValueKind.Null) return null;
        if (value.ValueKind == JsonValueKind.String && value.GetString() is { } key && BannerColors.Contains(key, StringComparer.Ordinal)) return key;
        errors.Add("banner_color", $"should be one of {string.Join(", ", BannerColors)}");
        return null;
    }

    /// <summary>Only DataGSM sync sets the GitHub login that authorizes GitHub actions; a patch may only echo the stored value.</summary>
    internal static void EnsureGithubUnchanged(JsonElement? requested, string? current)
    {
        if (requested is { } value && !(TryCastString(value, out var github) && github == current))
            throw ApiError.Validation("github_id는 수정할 수 없습니다.");
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

    static readonly Dictionary<string, int> ProfileFieldMaxLengths = new()
    {
        ["name"] = NameMaxLength,
        ["nickname"] = NicknameMaxLength,
        ["description"] = DescriptionMaxLength,
    };

    internal static Dictionary<string, string?> CastStrings(IReadOnlyDictionary<string, JsonElement> fields, FieldErrors errors)
    {
        var cast = new Dictionary<string, string?>();
        foreach (var (field, value) in fields)
        {
            if (!TryCastString(value, out var text)) errors.Add(field, "is invalid");
            else if (text is not null && ProfileFieldMaxLengths.TryGetValue(field, out var max) && TextLength(text) > max)
                errors.Add(field, $"should be at most {max} character(s)");
            else cast[field] = text;
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
            EnsureGithubUnchanged(patch.Github, account.Github);
            var profile = await connection.QueryAsync<(long Id, string? Nickname, string? Description, string? BannerColor)>(
                "SELECT id, nickname, description, banner_color FROM tb_profiles WHERE account_id = @userId FOR UPDATE", new { userId }, transaction).FirstOrNullAsync() ?? throw ApiError.NotFound();

            var profileErrors = new FieldErrors();
            var profileChanges = CastStrings(patch.Profile, profileErrors);
            var bannerColor = patch.BannerColor is { } rawBannerColor ? CastBannerColor(rawBannerColor, profileErrors) : profile.BannerColor;
            profileErrors.ThrowIfAny();
            var profileChanged = (profileChanges.TryGetValue("nickname", out var nickname) && nickname != profile.Nickname)
                                 | (profileChanges.TryGetValue("description", out var description) && description != profile.Description);
            // The profile event carries no banner color, so a banner-only change publishes nothing.
            var bannerChanged = bannerColor != profile.BannerColor;
            if (profileChanged || bannerChanged)
            {
                await connection.ExecuteAsync("""
                    UPDATE tb_profiles
                    SET nickname = @nickname, description = @description, banner_color = @bannerColor,
                        last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = @id
                    """, new
                {
                    nickname = profileChanges.GetValueOrDefault("nickname", profile.Nickname),
                    description = profileChanges.GetValueOrDefault("description", profile.Description),
                    bannerColor,
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
            var accountChanged = accountChanges.TryGetValue("name", out var name) && name != account.Name;
            if (accountChanged)
            {
                if (name is null)
                    throw ApiError.Validation("name can't be blank");
                await connection.ExecuteAsync("""
                    UPDATE tb_accounts
                    SET name = @name, last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = @userId
                    """, new { name, userId }, transaction);
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
        if (await connection.ExecuteScalarAsync<long?>(
                "SELECT a.id FROM tb_profiles p JOIN tb_accounts a ON a.id = p.account_id WHERE p.account_id = @userId", new { userId }) is null)
            throw ApiError.NotFound();

        var change = CustomStatusChange(attrs, rawStatus);
        // Only the fields in the request are written, so a concurrent PATCH of another field is not reverted.
        var assignments = new List<string> { "custom_status = @CustomStatus" };
        if (change.MessageGiven) assignments.Add("status_message = @StatusMessage");
        if (change.ExpiresAtGiven) assignments.Add("status_expires_at = @StatusExpiresAt");
        await connection.ExecuteAsync($"""
            UPDATE tb_accounts
            SET {string.Join(", ", assignments)}, last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6)
            WHERE id = @userId
            """, new { change.CustomStatus, change.StatusMessage, change.StatusExpiresAt, userId });

        return await GetUserProfileAsync(connection, userId);
    }

    /// <summary>The requested change; a field missing from the request keeps its stored value.</summary>
    internal sealed record StatusChange(string CustomStatus, bool MessageGiven, string? StatusMessage, bool ExpiresAtGiven, DateTime? StatusExpiresAt);

    internal static StatusChange CustomStatusChange(JsonElement attrs, JsonElement rawStatus)
    {
        var errors = new FieldErrors();
        if (!TryCastString(rawStatus, out var customStatus)) errors.Add("custom_status", "is invalid");
        else if (customStatus is null) errors.Add("custom_status", "can't be blank");
        else if (TextLength(customStatus) > CustomStatusMaxLength) errors.Add("custom_status", $"should be at most {CustomStatusMaxLength} character(s)");

        string? message = null;
        var messageGiven = attrs.TryGetProperty("message", out var rawMessage);
        if (messageGiven)
        {
            if (!TryCastString(rawMessage, out message)) errors.Add("status_message", "is invalid");
            else if (message is not null && TextLength(message) > StatusMessageMaxLength)
                errors.Add("status_message", $"should be at most {StatusMessageMaxLength} character(s)");
        }

        DateTime? expiresAt = null;
        var expiresAtGiven = attrs.TryGetProperty("expiresAt", out var rawExpires);
        if (expiresAtGiven)
        {
            if (rawExpires.ValueKind == JsonValueKind.Null) expiresAt = null;
            else if (rawExpires.ValueKind == JsonValueKind.String && rawExpires.GetString()!.Trim() == "") expiresAt = null;
            else if (rawExpires.ValueKind == JsonValueKind.String && Iso8601.TryParse(rawExpires.GetString(), out var parsed, assumeUtc: true))
                expiresAt = parsed.AddTicks(-(parsed.Ticks % TimeSpan.TicksPerSecond));
            else errors.Add("status_expires_at", "is invalid");
        }

        errors.ThrowIfAny();
        return new StatusChange(customStatus!, messageGiven, message, expiresAtGiven, expiresAt);
    }

    /// <summary>Length in Unicode code points, matching how MySQL VARCHAR counts length.</summary>
    internal static int TextLength(string text) => text.EnumerateRunes().Count();

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
        int updated;
        await using (var connection = await App.OpenAsync())
            updated = await connection.ExecuteAsync(
                "UPDATE tb_profiles SET profile_image_key = @finalKey, last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6) WHERE id = @id AND profile_image_key <=> @oldKey",
                new { finalKey, userId, id = profile.Id, oldKey = profile.ImageKey });

        // A concurrent confirm replaced the key first, so this copy is referenced nowhere.
        if (updated == 0)
        {
            await ObjectStorage.DeleteObjectAsync(finalKey);
            await ObjectStorage.DeleteObjectAsync(objectKey);
            throw new ApiError(409, "다른 요청이 프로필 이미지를 먼저 변경했습니다. 이미지를 다시 업로드하세요.");
        }

        if (profile.ImageKey is not null && profile.ImageKey != finalKey) await ObjectStorage.DeleteObjectAsync(profile.ImageKey);
        await ObjectStorage.DeleteObjectAsync(objectKey);
    }

    public static async Task DeleteProfileImageAsync(long userId)
    {
        await using var connection = await App.OpenAsync();
        var profile = await LoadProfileImageAsync(connection, userId);
        if (profile.ImageKey is null) return;
        var updated = await connection.ExecuteAsync(
            "UPDATE tb_profiles SET profile_image_key = NULL, last_modified_by = @userId, updated_at = UTC_TIMESTAMP(6) WHERE id = @id AND profile_image_key <=> @oldKey",
            new { userId, id = profile.Id, oldKey = profile.ImageKey });
        // A concurrent confirm replaced the key, so deleting now would orphan the new file or drop the one in use.
        if (updated == 0) throw new ApiError(409, "다른 요청이 프로필 이미지를 먼저 변경했습니다. 다시 시도하세요.");
        await ObjectStorage.DeleteObjectAsync(profile.ImageKey);
    }

    public sealed record SessionIdentity(long UserId, string Email, string GsmRole);

    /// <summary>Commits a login or link command. A null identity means FAILED, with a failure code when an account conflict caused it.</summary>
    public static async Task<(SessionIdentity? Identity, string? FailureCode)> ApplyIdentityCommandAsync(
        MySqlConnection connection, MySqlTransaction transaction, IdentityCommandContract.Command command)
    {
        if (command.CommandType == "LINK") return await ApplyLinkCommandAsync(connection, transaction, command);

        var binding = await FindIdentityAsync(connection, transaction, command);
        var id = binding?.Id;

        // A GSM student who signs up with GitHub first gets an account without student information. Send the student to
        // DataGSM, and let them link GitHub from that account. A GitHub account that is already linked signs in as usual.
        if (binding is null && command.Provider == "GITHUB" && command.GsmEmailVerified)
            return (null, IdentityCommand.DataGsmLoginRequiredCode);

        if (binding is { } existing && !CanApplyLoginCommand(existing.LastCommandAt, command.OccurredAt))
            return (await ReadSessionIdentityAsync(connection, transaction, existing.Id), null);

        // Keep the inbox's FAILED result, but roll back every account mutation on a unique-key collision.
        await transaction.SaveAsync("identity_write");
        var isNew = id is null;
        try
        {
            if (isNew)
            {
                id = await connection.ExecuteScalarAsync<long>("""
                    INSERT INTO tb_accounts (name, email, github, presence_updated_at)
                    VALUES (@Name, @Email, @github, '1970-01-01 00:00:00');
                    SELECT LAST_INSERT_ID();
                    """, new { command.Name, command.Email, github = NullIfBlank(command.GithubId) }, transaction);
                await connection.ExecuteAsync("""
                    INSERT INTO tb_account_identities (account_id, provider, provider_subject) VALUES (@id, @Provider, @ProviderSubject);
                    INSERT INTO tb_profiles (account_id) VALUES (@id);
                    UPDATE tb_accounts SET created_by = @id, last_modified_by = @id WHERE id = @id;
                    """, new { id, command.Provider, command.ProviderSubject }, transaction);
            }

            // GitHub and developer logins never overwrite the profile or student information of an existing account.
            if (command.Provider == "DATAGSM")
            {
                if (isNew)
                    await InsertStudentAsync(connection, transaction, id!.Value, command);
                else
                    await connection.ExecuteAsync($"""
                        UPDATE tb_accounts a JOIN tb_gsm_student_info s ON s.account_id = a.id
                        SET a.name = @Name, a.email = @Email, a.github = {GithubUnlessLinked}, a.last_modified_by = @id, a.updated_at = UTC_TIMESTAMP(6),
                            s.sex = @Sex, s.student_role = @Role, s.student_number = @studentNumber,
                            s.datagsm_student_id = COALESCE(@DatagsmStudentId, s.datagsm_student_id), s.major = @Major
                        WHERE a.id = @id
                        """, new { id, command.Name, command.Email, github = NullIfBlank(command.GithubId),
                        command.DatagsmStudentId, command.Sex, command.Role, command.Major,
                        studentNumber = StudentNumber(command.Grade, command.ClassNumber, command.StudentNumberInClass) }, transaction);
            }

            var userId = id!.Value;
            await connection.ExecuteAsync("""
                UPDATE tb_account_identities SET last_login_command_at = @OccurredAt
                WHERE account_id = @userId AND provider = @Provider
                """, new { userId, command.Provider, command.OccurredAt }, transaction);
            await PresenceProjection.ApplyLatestToAccountAsync(connection, transaction, userId);
            await ProfileEventPublisher.EnqueueCurrentUpsertAsync(connection, transaction, userId);
            return (await ReadSessionIdentityAsync(connection, transaction, userId), null);
        }
        catch (MySqlException error) when (error.ErrorCode == MySqlErrorCode.DuplicateKeyEntry)
        {
            await transaction.RollbackAsync("identity_write");
            if (!isNew) return (null, null);
            // A concurrent first login for the same identity committed first, so this command signs in to that account.
            if (await FindIdentityAsync(connection, transaction, command) is { } concurrent)
                return (await ReadSessionIdentityAsync(connection, transaction, concurrent.Id), null);
            // Another account already uses this email, GitHub ID, or student; an unlinked login method must not reach it.
            // A student whose account has no student row signed up with GitHub first and must link DataGSM from that account.
            if (command.Provider == "DATAGSM" && await connection.ExecuteScalarAsync<bool>("""
                    SELECT EXISTS (SELECT 1 FROM tb_accounts a LEFT JOIN tb_gsm_student_info s ON s.account_id = a.id
                                   WHERE (a.email = @Email OR a.github = @github) AND s.account_id IS NULL)
                    """, new { command.Email, github = NullIfBlank(command.GithubId) }, transaction))
                return (null, IdentityCommand.GitHubLoginRequiredCode);
            return (null, IdentityCommand.NotLinkedCode);
        }
    }

    // DataGSM never replaces the github value of an account that has a GitHub login method, because GitHub verified it.
    const string GithubUnlessLinked =
        "IF(EXISTS (SELECT 1 FROM tb_account_identities i WHERE i.account_id = a.id AND i.provider = 'GITHUB'), a.github, @github)";

    static Task InsertStudentAsync(MySqlConnection connection, MySqlTransaction transaction, long accountId, IdentityCommandContract.Command command) =>
        connection.ExecuteAsync("""
            INSERT INTO tb_gsm_student_info (account_id, datagsm_student_id, sex, student_role, major, student_number)
            VALUES (@accountId, @DatagsmStudentId, @Sex, @Role, @Major, @studentNumber)
            """, new { accountId, command.DatagsmStudentId, command.Sex, command.Role, command.Major,
            studentNumber = StudentNumber(command.Grade, command.ClassNumber, command.StudentNumberInClass) }, transaction);

    static Task<(long Id, DateTime? LastCommandAt)?> FindIdentityAsync(
        MySqlConnection connection, MySqlTransaction transaction, IdentityCommandContract.Command command) =>
        connection.QueryAsync<(long Id, DateTime? LastCommandAt)>("""
            SELECT account_id, last_login_command_at FROM tb_account_identities
            WHERE provider = @Provider AND provider_subject = @ProviderSubject FOR UPDATE
            """, new { command.Provider, command.ProviderSubject }, transaction).FirstOrNullAsync();

    /// <summary>
    /// Adds a login method to the account that requested it. The common account profile does not change.
    /// A DataGSM link also adds the student row. A method or student linked to another account, a second method of the
    /// same provider, or a DataGSM link to an account that already has a student row, is refused.
    /// </summary>
    static async Task<(SessionIdentity? Identity, string? FailureCode)> ApplyLinkCommandAsync(
        MySqlConnection connection, MySqlTransaction transaction, IdentityCommandContract.Command command)
    {
        // Lock the account before its identities, as profile writes do.
        var accountId = await connection.QuerySingleOrDefaultAsync<long?>(
            "SELECT id FROM tb_accounts WHERE id = @AccountId FOR UPDATE", new { command.AccountId }, transaction);
        if (accountId is null) return (null, null);

        var linkedTo = (await FindIdentityAsync(connection, transaction, command))?.Id;
        if (linkedTo == accountId) return (await ReadSessionIdentityAsync(connection, transaction, accountId.Value), null);
        if (linkedTo is not null) return (null, IdentityCommand.AlreadyLinkedCode);

        await transaction.SaveAsync("identity_link");
        try
        {
            await connection.ExecuteAsync("""
                INSERT INTO tb_account_identities (account_id, provider, provider_subject, last_login_command_at)
                VALUES (@accountId, @Provider, @ProviderSubject, @OccurredAt)
                """, new { accountId, command.Provider, command.ProviderSubject, command.OccurredAt }, transaction);
            if (command.Provider == "DATAGSM")
            {
                await InsertStudentAsync(connection, transaction, accountId.Value, command);
                await ProfileEventPublisher.EnqueueCurrentUpsertAsync(connection, transaction, accountId.Value);
            }
        }
        catch (MySqlException error) when (error.ErrorCode == MySqlErrorCode.DuplicateKeyEntry)
        {
            // uq_tb_account_identities_account_id_provider: the account already has a login method of this provider.
            // tb_gsm_student_info PRIMARY or uq_tb_gsm_student_info_datagsm_student_id: the account already has a student
            // row, or another account has this student.
            await transaction.RollbackAsync("identity_link");
            return (null, IdentityCommand.AlreadyLinkedCode);
        }
        return (await ReadSessionIdentityAsync(connection, transaction, accountId.Value), null);
    }

    internal static bool CanApplyLoginCommand(DateTime? lastCommandAt, DateTime occurredAt) =>
        lastCommandAt is null || occurredAt > lastCommandAt.Value;

    static Task<SessionIdentity> ReadSessionIdentityAsync(MySqlConnection connection, MySqlTransaction transaction, long userId) =>
        connection.QuerySingleAsync<SessionIdentity>("""
            SELECT a.id AS UserId, a.email AS Email, COALESCE(s.student_role, '') AS GsmRole
            FROM tb_accounts a LEFT JOIN tb_gsm_student_info s ON s.account_id = a.id WHERE a.id = @userId FOR SHARE
            """, new { userId }, transaction);

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
        Iso8601.TryParse(payload.GetString("occurred_at"), out var occurredAt);

        return await App.TransactionAsync(async (connection, transaction) =>
        {
            // Look up the student without locking it, then lock the account before the student, as profile writes do.
            var accountId = await connection.QuerySingleOrDefaultAsync<long?>("""
                SELECT id FROM tb_accounts
                WHERE id = (SELECT account_id FROM tb_gsm_student_info WHERE datagsm_student_id = @studentId)
                FOR UPDATE
                """, new { studentId }, transaction);
            if (accountId is null) return StudentEventResult.AccountNotFound;
            var account = await connection.QueryAsync<(long Id, DateTime? DatagsmUpdatedAt)>("""
                SELECT account_id, datagsm_updated_at FROM tb_gsm_student_info
                WHERE account_id = @accountId AND datagsm_student_id = @studentId FOR UPDATE
                """, new { accountId, studentId }, transaction).FirstOrNullAsync();
            if (account is null) throw new InvalidOperationException("Student identity changed while locking its account");
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
                await connection.ExecuteAsync($"""
                    UPDATE tb_accounts a JOIN tb_gsm_student_info s ON s.account_id = a.id
                    SET a.name = @name, a.email = @email, s.sex = @sex, a.github = {GithubUnlessLinked}, s.student_role = @studentRole,
                        s.student_number = @studentNumber, s.major = @major, s.specialty = @specialty,
                        s.datagsm_updated_at = @occurredAt, a.updated_at = UTC_TIMESTAMP(6)
                    WHERE a.id = @id
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
            $"SELECT COUNT(DISTINCT p.id) {ProfileFrom} {where}", parameters);

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
        Equal("s.major", "major");
        Equal("s.student_role", "student_role");
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
            "major" => $"s.major {direction}",
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
