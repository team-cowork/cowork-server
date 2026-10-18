namespace CoworkUser.Tests;

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

    [Fact]
    public void Iso8601_rejects_zone_offsets_outside_23_59()
    {
        Assert.False(Iso8601.TryParse("2026-05-01T00:00:00+99:99", out _, out _));
        Assert.False(Iso8601.TryParse("2026-05-01T00:00:00+24:00", out _, out _));
        Assert.False(Iso8601.TryParse("2026-05-01T00:00:00+23:60", out _, out _));
        Assert.True(Iso8601.TryParse("2026-05-01T00:00:00-23:59", out var west, out _));
        Assert.Equal(new DateTime(2026, 5, 1, 23, 59, 0, DateTimeKind.Utc), west);
    }
}
