using System.Text.Json;

namespace CoworkUser.Tests;

public class AccountsPolicyTests
{
    static JsonElement Json(string json) => JsonDocument.Parse(json).RootElement;

    [Fact]
    public void ParseIntCsv_keeps_only_positive_integers()
    {
        Assert.Equal([1, 2, 3], Accounts.ParseIntCsv("1,2,3"));
        Assert.Equal([1, 2, 3], Accounts.ParseIntCsv(" 1 , 2 ,3 "));
        Assert.Equal([1, 2], Accounts.ParseIntCsv("1,,2,"));
        Assert.Equal([1, 2], Accounts.ParseIntCsv("1,0,-5,2"));
        Assert.Equal([1, 2], Accounts.ParseIntCsv("1,abc,2,1.5,3x"));
        Assert.Empty(Accounts.ParseIntCsv(""));
        Assert.Empty(Accounts.ParseIntCsv(",,,"));
        Assert.Equal([1, 1, 1], Accounts.ParseIntCsv("1,1,1"));
    }

    [Fact]
    public void ParseIntCsv_does_not_parse_tokens_beyond_the_limit()
    {
        var result = Accounts.ParseIntCsv(string.Join(",", Enumerable.Range(1, 1002)));
        Assert.Equal(Enumerable.Range(1, 1000).Select(i => (long)i), result);
    }

    [Fact]
    public void Profile_patch_keeps_omitted_fields_absent_and_explicit_null()
    {
        var patch = Accounts.ParseProfilePatch(Json("""{"nickname": null, "name": "김코워크"}"""));
        Assert.Equal(["nickname"], patch.Profile.Keys);
        Assert.Equal(JsonValueKind.Null, patch.Profile["nickname"].ValueKind);
        Assert.Equal(["name"], patch.Account.Keys);
        Assert.Null(patch.Roles);

        Assert.Equal(["github"], Accounts.ParseProfilePatch(Json("""{"github_id": "octo"}""")).Account.Keys);
    }

    [Fact]
    public void Profile_patch_roles_are_trimmed_deduplicated_and_sorted()
    {
        Assert.Empty(Accounts.ParseProfilePatch(Json("""{"roles": []}""")).Roles!);
        Assert.Equal(["ADMIN", "MEMBER"], Accounts.ParseProfilePatch(Json("""{"roles": [" MEMBER", "ADMIN", "MEMBER ", " "]}""")).Roles);
        Assert.Equal("roles는 배열이어야 합니다.", Assert.Throws<ApiError>(() => Accounts.ParseProfilePatch(Json("""{"roles": null}"""))).Message);
        Assert.Equal("roles는 배열이어야 합니다.", Assert.Throws<ApiError>(() => Accounts.ParseProfilePatch(Json("""{"roles": "ADMIN"}"""))).Message);
        Assert.Equal("roles의 각 값은 문자열이어야 합니다.", Assert.Throws<ApiError>(() => Accounts.ParseProfilePatch(Json("""{"roles": ["ADMIN", 1]}"""))).Message);
    }

    [Fact]
    public void Blank_strings_cast_to_null_and_non_strings_are_invalid()
    {
        Assert.True(Accounts.TryCastString(Json("\"  \""), out var blank));
        Assert.Null(blank);
        Assert.True(Accounts.TryCastString(Json("\" kim \""), out var text));
        Assert.Equal(" kim ", text);
        Assert.False(Accounts.TryCastString(Json("1"), out _));
    }

    [Fact]
    public void Custom_status_change_keeps_omitted_fields_and_validates_lengths()
    {
        var expiresAt = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        var attrs = Json("""{"custom_status": "DO_NOT_DISTURB", "expiresAt": "2026-08-26T18:00:00.123+09:00"}""");
        var change = Accounts.CustomStatusChange(attrs, attrs.GetProperty("custom_status"), "집중 중", expiresAt);
        Assert.Equal("DO_NOT_DISTURB", change.CustomStatus);
        Assert.Equal("집중 중", change.StatusMessage);
        Assert.Equal(new DateTime(2026, 8, 26, 9, 0, 0, DateTimeKind.Utc), change.StatusExpiresAt);

        var tooLong = Json($$"""{"custom_status": "{{new string('a', 31)}}", "message": "{{new string('가', 101)}}"}""");
        var error = Assert.Throws<FieldErrors>(() => Accounts.CustomStatusChange(tooLong, tooLong.GetProperty("custom_status"), null, null));
        Assert.Equal("custom_status should be at most 30 character(s); status_message should be at most 100 character(s)", error.Message);

        var blank = Json("""{"custom_status": ""}""");
        Assert.Equal("custom_status can't be blank",
            Assert.Throws<FieldErrors>(() => Accounts.CustomStatusChange(blank, blank.GetProperty("custom_status"), null, null)).Message);
    }

    [Fact]
    public void Search_filters_accept_only_presence_enum_and_bounded_custom_status()
    {
        Accounts.ValidateSearchFilters(new Dictionary<string, string>());
        Accounts.ValidateSearchFilters(new Dictionary<string, string> { ["status"] = "online" });
        Accounts.ValidateSearchFilters(new Dictionary<string, string> { ["custom_status"] = new string('a', 30) });
        Assert.Contains("offline", Assert.Throws<ApiError>(() => Accounts.ValidateSearchFilters(new Dictionary<string, string> { ["status"] = "away" })).Message);
        Assert.Contains("30", Assert.Throws<ApiError>(() => Accounts.ValidateSearchFilters(new Dictionary<string, string> { ["custom_status"] = new string('a', 31) })).Message);
    }

    [Fact]
    public async Task Team_search_is_scoped_to_active_members()
    {
        Task<IReadOnlyList<long>> Members(long team, long requester) =>
            Task.FromResult<IReadOnlyList<long>>(team == 11 && requester == 7 ? [7, 9] : []);

        Assert.Null(await Accounts.AuthorizeTeamSearchAsync(7, new Dictionary<string, string> { ["q"] = "kim" }, Members));
        Assert.Equal([7, 9], await Accounts.AuthorizeTeamSearchAsync(7, new Dictionary<string, string> { ["teamId"] = "11" }, Members));
        Assert.Equal(403, (await Assert.ThrowsAsync<ApiError>(() => Accounts.AuthorizeTeamSearchAsync(8, new Dictionary<string, string> { ["teamId"] = "11" }, Members))).Status);
        Assert.Equal(400, (await Assert.ThrowsAsync<ApiError>(() => Accounts.AuthorizeTeamSearchAsync(7, new Dictionary<string, string> { ["teamId"] = "x" }, Members))).Status);
        Assert.Equal(503, (await Assert.ThrowsAsync<ApiError>(() => Accounts.AuthorizeTeamSearchAsync(7, new Dictionary<string, string> { ["teamId"] = "11" },
            (_, _) => throw new InvalidOperationException("database unavailable")))).Status);
    }

    [Fact]
    public void Search_terms_are_trimmed_and_like_wildcards_escaped()
    {
        Assert.Equal("김코워크", Accounts.NormalizeSearchTerm("  김코워크  "));
        Assert.Null(Accounts.NormalizeSearchTerm("\t\n"));
        Assert.Null(Accounts.NormalizeSearchTerm(null));
        Assert.Equal("kim", Accounts.SearchTerm("  ", "kim"));
        Assert.Equal("%kim%", Accounts.LikePattern("kim"));
        Assert.Equal(@"%100\%%", Accounts.LikePattern("100%"));
        Assert.Equal(@"%a\_b%", Accounts.LikePattern("a_b"));
        Assert.Equal(@"%a\\b%", Accounts.LikePattern(@"a\b"));
        Assert.Equal(@"%\\\%%", Accounts.LikePattern(@"\%"));
    }

    [Fact]
    public void Student_events_apply_only_when_newer()
    {
        var current = new DateTime(2026, 5, 1, 0, 0, 0, DateTimeKind.Utc);
        Assert.True(Accounts.StudentEventNewer(null, current));
        Assert.True(Accounts.StudentEventNewer(current, current.AddTicks(10)));
        Assert.False(Accounts.StudentEventNewer(current, current));
        Assert.False(Accounts.StudentEventNewer(current, current.AddSeconds(-1)));
    }

    [Fact]
    public void Student_number_pads_the_number_in_class()
    {
        Assert.Equal("2105", Accounts.StudentNumber(2, 1, 5));
        Assert.Null(Accounts.StudentNumber(2, null, 5));
    }
}
