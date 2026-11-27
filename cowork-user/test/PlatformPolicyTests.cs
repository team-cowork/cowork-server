using CoworkUser.Http;
using CoworkUser.Platform;
using CoworkUser.Users;

namespace CoworkUser.Tests;

public class ObjectStoragePolicyTests
{
    [Theory]
    [InlineData(null)]
    [InlineData(0L)]
    [InlineData(-1L)]
    public void Missing_or_non_positive_sizes_are_rejected(long? size) =>
        Assert.Equal(400, Assert.Throws<ApiError>(() => ObjectStorage.ValidateMetadata(size, "image/png", "v", 1000)).Status);

    [Fact]
    public void Oversized_disallowed_or_unverifiable_uploads_are_rejected()
    {
        Assert.Equal(413, Assert.Throws<ApiError>(() => ObjectStorage.ValidateMetadata(1001, "image/png", "v", 1000)).Status);
        Assert.Equal(400, Assert.Throws<ApiError>(() => ObjectStorage.ValidateMetadata(100, "image/gif", "v", 1000)).Status);
        Assert.Equal(400, Assert.Throws<ApiError>(() => ObjectStorage.ValidateMetadata(100, "image/png", "", 1000)).Status);
        Assert.Equal(new ObjectStorage.UploadMetadata("image/png", "version"), ObjectStorage.ValidateMetadata(100, "image/png", "version", 1000));
    }

    [Fact]
    public void Upload_keys_for_every_allowed_type_pass_the_key_check()
    {
        // Presign and confirm share one type list, so every key presign issues is accepted at confirm.
        foreach (var type in new[] { "image/jpeg", "image/png", "image/webp" })
        {
            Assert.True(ObjectStorage.ContentTypeAllowed(type));
            Assert.True(ObjectStorage.OwnedKey(ObjectStorage.BuildObjectKey(7, type), "profile-uploads", 7));
        }
        Assert.False(ObjectStorage.ContentTypeAllowed("image/gif"));
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

    [Theory]
    [InlineData("2026-10-18T10:00:00+09:00", "2026-10-18T01:00:00.000000Z")]
    [InlineData("2026-10-18T10:00:00.5+09:00", "2026-10-18T01:00:00.500000Z")]
    [InlineData("2026-10-18T10:00:00.123456789+09:00", "2026-10-18T01:00:00.123456Z")]
    public void Timestamps_are_normalized_to_utc_microseconds(string input, string expected)
    {
        Assert.True(Iso8601.TryParse(input, out var parsed));
        Assert.Equal(expected, Iso8601.Format(parsed));
    }

    [Fact]
    public void Timestamps_without_an_offset_require_an_explicit_utc_default()
    {
        Assert.False(Iso8601.TryParse("2026-10-18T10:00:00", out _));
        Assert.True(Iso8601.TryParse("2026-10-18 10:00:00", out var naive, assumeUtc: true));
        Assert.Equal(new DateTime(2026, 10, 18, 10, 0, 0, DateTimeKind.Utc), naive);
    }

    [Fact]
    public void Iso8601_rejects_zone_offsets_outside_23_59()
    {
        Assert.False(Iso8601.TryParse("2026-05-01T00:00:00+99:99", out _));
        Assert.False(Iso8601.TryParse("2026-05-01T00:00:00+24:00", out _));
        Assert.False(Iso8601.TryParse("2026-05-01T00:00:00+23:60", out _));
        Assert.True(Iso8601.TryParse("2026-05-01T00:00:00-23:59", out var west));
        Assert.Equal(new DateTime(2026, 5, 1, 23, 59, 0, DateTimeKind.Utc), west);
    }
}
