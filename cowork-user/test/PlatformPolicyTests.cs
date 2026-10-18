namespace CoworkUser.Tests;

public class SchemaMigrationTests
{
    static readonly string Migrations = Path.Combine(AppContext.BaseDirectory, "db", "migration");

    [Fact]
    public void Checksums_and_lock_name_match_the_flyway_history()
    {
        var migrations = SchemaMigration.LoadMigrations(Migrations);
        Assert.Equal(Enumerable.Range(1, migrations.Count), migrations.Select(m => m.Version));
        Assert.Equal(-804017080, migrations[0].Checksum);
        Assert.Equal("init", migrations[0].Description);
        Assert.Equal(-1217367336, migrations.Single(m => m.Version == 16).Checksum);
        Assert.Equal("Flyway-1424112396", SchemaMigration.LockName("cowork_user"));
    }

    [Fact]
    public void Statements_split_only_outside_literals_and_comments()
    {
        var statements = SchemaMigration.ValidateSql("UPDATE t SET a = 'x;y'; -- c;\nINSERT INTO t VALUES (1);", "V1__x.sql");
        Assert.Equal(["UPDATE t SET a = 'x;y'", " -- c;\nINSERT INTO t VALUES (1)"], statements);
        Assert.Throws<SchemaMigration.Error>(() => SchemaMigration.ValidateSql("SELECT GET_LOCK('a', 1);", "V1__x.sql"));
        Assert.Throws<SchemaMigration.Error>(() => SchemaMigration.ValidateSql("CREATE PROCEDURE p() BEGIN END;", "V1__x.sql"));
    }
}

public class ObjectStoragePolicyTests
{
    static readonly string[] Allowed = ["image/jpeg", "image/png", "image/webp"];

    [Theory]
    [InlineData(null)]
    [InlineData(0L)]
    [InlineData(-1L)]
    public void Missing_or_non_positive_sizes_are_rejected(long? size) =>
        Assert.Equal(400, Assert.Throws<ApiError>(() => ObjectStorage.ValidateMetadata(size, "image/png", "v", 1000, Allowed)).Status);

    [Fact]
    public void Oversized_disallowed_or_unverifiable_uploads_are_rejected()
    {
        Assert.Equal(413, Assert.Throws<ApiError>(() => ObjectStorage.ValidateMetadata(1001, "image/png", "v", 1000, Allowed)).Status);
        Assert.Equal(400, Assert.Throws<ApiError>(() => ObjectStorage.ValidateMetadata(100, "image/gif", "v", 1000, [.. Allowed, "image/gif"])).Status);
        Assert.Equal(400, Assert.Throws<ApiError>(() => ObjectStorage.ValidateMetadata(100, "image/png", "", 1000, Allowed)).Status);
        Assert.Equal(new ObjectStorage.UploadMetadata("image/png", "version"), ObjectStorage.ValidateMetadata(100, "image/png", "version", 1000, Allowed));
    }

    [Fact]
    public void Upload_keys_must_belong_to_the_user()
    {
        const string key = "profile-uploads/7/0b0f4c8e-6f0e-4a8e-9d3b-1f0c2a9b7c11.png";
        Assert.True(ObjectStorage.OwnedKey(key, "profile-uploads", 7));
        Assert.False(ObjectStorage.OwnedKey(key, "profile-uploads", 8));
        Assert.False(ObjectStorage.OwnedKey("profile-uploads/7/../8/x.png", "profile-uploads", 7));
    }
}

public class HttpPolicyTests
{
    [Theory]
    [InlineData("/users/me", true)]
    [InlineData("/users/batch", true)]
    [InlineData("/users/search", true)]
    [InlineData("/users/by-github/octo", true)]
    [InlineData("/users/by-github/", false)]
    [InlineData("/users/42", true)]
    [InlineData("/users/0", false)]
    [InlineData("/users/abc", false)]
    [InlineData("/actuator/health", false)]
    public void Projection_dependent_reads_are_gated(string path, bool gated) =>
        Assert.Equal(gated, Router.ProjectionDependentRead(path));

    [Fact]
    public void Batch_ids_are_deduplicated_and_capped()
    {
        Assert.Equal([1, 2], Router.ParseIds("1,2,1"));
        Assert.Equal(100, Router.ParseIds(string.Join(",", Enumerable.Range(1, 150))).Count);
        Assert.Equal("ids 파라미터가 필요합니다.", Assert.Throws<ApiError>(() => Router.ParseIds(null)).Message);
        Assert.Equal("유효한 ids가 없습니다.", Assert.Throws<ApiError>(() => Router.ParseIds("a,0")).Message);
    }

    [Fact]
    public void Iso8601_keeps_the_fraction_precision_it_was_given()
    {
        Assert.True(Iso8601.TryParse("2026-10-18T10:00:00.5+09:00", out var parsed, out var precision));
        Assert.Equal("2026-10-18T01:00:00.5Z", Iso8601.Format(parsed, precision));
        Assert.False(Iso8601.TryParse("2026-10-18T10:00:00", out _, out _));
        Assert.True(Iso8601.TryParse("2026-10-18 10:00:00", out var naive, out _, assumeUtc: true));
        Assert.Equal(new DateTime(2026, 10, 18, 10, 0, 0, DateTimeKind.Utc), naive);
        Assert.Equal("2026-10-18T01:00:00.000000Z", Iso8601.Format(parsed.AddTicks(-5_000_000)));
    }
}
