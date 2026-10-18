using System.Text;
using System.Text.Json;
using CoworkUser.Kafka;
using static CoworkUser.Kafka.ProjectionCheckpoint;

namespace CoworkUser.Tests;

public class ProjectionReadinessTests
{
    const long Generation = 16;
    const string Token = "45985bb2-8689-45c0-b23b-399e754e1e0c";
    const string SnapshotId = "e71bf570-06e2-443c-9a9c-c0cdd666ba2d";

    static ProjectionReadiness.Range Range(long end, long marker, long startupEnd) =>
        new(0, end, startupEnd, Generation, new ProjectionReadiness.ValidatedMarker(marker, Generation, Token, SnapshotId));

    static State Checkpoint(long next, long marker) => new(next, Generation, Token, null, marker, SnapshotId, null, true);

    static Marker MarkerAt(long offset) => new(offset, Generation, Token, SnapshotId, "cowork-team");

    [Fact]
    public void Stays_ready_while_the_source_publishes_past_the_startup_high_watermark() =>
        Assert.True(ProjectionReadiness.PartitionReady(Range(1120, 1114, 1115), Checkpoint(1115, 1114), MarkerAt(1114)));

    [Fact]
    public void Stops_being_ready_when_the_checkpoint_trails_the_live_high_watermark_too_far()
    {
        Assert.True(ProjectionReadiness.PartitionReady(Range(2115, 1114, 1115), Checkpoint(1115, 1114), MarkerAt(1114)));
        Assert.False(ProjectionReadiness.PartitionReady(Range(2116, 1114, 1115), Checkpoint(1115, 1114), MarkerAt(1114)));
    }

    [Fact]
    public void Is_ready_once_the_checkpoint_reaches_the_startup_high_watermark() =>
        Assert.True(ProjectionReadiness.PartitionReady(Range(1115, 1114, 1115), Checkpoint(1115, 1114), MarkerAt(1114)));

    [Fact]
    public void Rejects_unfinished_or_stale_replays()
    {
        Assert.False(ProjectionReadiness.PartitionReady(Range(1115, 1088, 1115), Checkpoint(1089, 1088), MarkerAt(1088)));
        Assert.False(ProjectionReadiness.PartitionReady(Range(1120, 1114, 1115), Checkpoint(1114, 1114), MarkerAt(1114)));
        Assert.False(ProjectionReadiness.PartitionReady(Range(1115, 1114, 1115), Checkpoint(1116, 1114), MarkerAt(1114)));
        Assert.False(ProjectionReadiness.PartitionReady(Range(1120, 1114, 1115), Checkpoint(1115, 1114), MarkerAt(1114) with { Generation = Generation - 1 }));
        Assert.False(ProjectionReadiness.PartitionReady(Range(1120, 1114, 1115), Checkpoint(1115, 1114) with { InvalidRecordOffset = 1113 }, MarkerAt(1114)));
        Assert.False(ProjectionReadiness.PartitionReady(Range(1120, 1114, 1115), Checkpoint(1115, 1114) with { LeaseActive = false }, MarkerAt(1114)));
        Assert.False(ProjectionReadiness.PartitionReady(Range(1120, 1114, 1115) with { Marker = null }, Checkpoint(1115, 1114), MarkerAt(1114)));
        Assert.False(ProjectionReadiness.PartitionReady(Range(1120, 1114, 1115), null, MarkerAt(1114)));
    }

    [Theory]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAA", true)]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB", false)]
    [InlineData(null, "BBBBBBBBBBBBBBBBBBBBBB", false)]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAA", null, false)]
    [InlineData(null, null, false)]
    public void Projection_data_survives_a_start_only_when_the_topic_identity_is_proven(string? stored, string? current, bool continuous) =>
        Assert.Equal(continuous, ProjectionCheckpoint.TopicContinuous(stored, current));

    [Theory]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAA", false)]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB", true)]
    [InlineData(null, "BBBBBBBBBBBBBBBBBBBBBB", true)]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAA", null, false)]
    [InlineData(null, null, false)]
    public void A_replaced_topic_is_detected_only_from_a_reported_uuid(string? stored, string? current, bool replaced) =>
        Assert.Equal(replaced, ProjectionCheckpoint.TopicReplaced(stored, current));

    [Fact]
    public void Checkpoints_and_markers_must_be_inside_the_retained_log()
    {
        Assert.True(ProjectionReadiness.CheckpointReplayable(10, 10, 20));
        Assert.True(ProjectionReadiness.CheckpointReplayable(20, 10, 20));
        Assert.False(ProjectionReadiness.CheckpointReplayable(21, 10, 20));
        Assert.True(ProjectionReadiness.MarkerReplayable(19, 10, 20));
        Assert.False(ProjectionReadiness.MarkerReplayable(20, 10, 20));
    }
}

public class ProjectionCheckpointTests
{
    static readonly RecoveryState Clean = new(null, null, null, null);

    [Fact]
    public void Invalid_record_latch_clears_after_two_distinct_snapshots_past_the_gap()
    {
        var latched = LatchInvalidRecord(Clean, 10);
        Assert.Equal(10, latched.InvalidRecordOffset);

        var beforeGap = ObserveRecoverySnapshot(latched, 9, "a");
        Assert.Equal(10, beforeGap.InvalidRecordOffset);
        Assert.Null(beforeGap.RecoverySnapshotId);

        var first = ObserveRecoverySnapshot(latched, 11, "a");
        Assert.Equal("a", first.RecoverySnapshotId);
        Assert.Equal(first, ObserveRecoverySnapshot(first, 11, "b"));
        Assert.Equal(10, ObserveRecoverySnapshot(first, 12, "a").InvalidRecordOffset);

        var cleared = ObserveRecoverySnapshot(first, 12, "b");
        Assert.Null(cleared.InvalidRecordOffset);
        Assert.Null(cleared.RecoverySnapshotId);
        Assert.Equal(12, cleared.SnapshotCompletedOffset);
    }

    [Fact]
    public void Older_invalid_offsets_keep_recovery_progress()
    {
        var progressing = new RecoveryState(10, 11, "a", "a");
        Assert.Equal(progressing, LatchInvalidRecord(progressing, 9));
        Assert.Equal(new RecoveryState(12, 11, "a", null), LatchInvalidRecord(progressing, 12));
    }

    [Fact]
    public void Assignment_claims_and_leases_are_fenced_by_generation_owner_and_expiry()
    {
        Assert.True(AssignmentClaimable(null, null, false, 3, "me"));
        Assert.True(AssignmentClaimable(2, "other", true, 3, "me"));
        Assert.True(AssignmentClaimable(3, "me", true, 3, "me"));
        Assert.True(AssignmentClaimable(3, "other", false, 3, "me"));
        Assert.False(AssignmentClaimable(3, "other", true, 3, "me"));

        Assert.True(LeaseMatches(3, "t", true, 3, "t"));
        Assert.False(LeaseMatches(3, "t", false, 3, "t"));
        Assert.False(LeaseMatches(4, "t", true, 3, "t"));
        Assert.False(LeaseMatches(3, "u", true, 3, "t"));
    }
}

public class ContractTests
{
    static JsonElement Json(string json) => JsonDocument.Parse(json).RootElement;

    const string IdentityCommand = """
        {"schemaVersion":1,"operationId":"0b0f4c8e-6f0e-4a8e-9d3b-1f0c2a9b7c11","idempotencyKey":"login:7",
         "commandType":"UPSERT","userId":7,"name":"김코워크","email":"kim@gsm.hs.kr","sex":"MAN","grade":2,
         "classNumber":1,"studentNumberInClass":5,"major":"SW_DEVELOPMENT","role":"GENERAL_STUDENT",
         "githubId":null,"dataGSMStudentId":42,"requestedBy":7,"occurredAt":"2026-10-18T10:00:00.120+09:00"}
        """;

    [Fact]
    public void Undecodable_strings_are_classified_as_invalid_records_instead_of_throwing()
    {
        var backslash = (char)92;
        byte[] invalidUtf8 = [.. "{\"name\":\""u8, 0xFF, .. "\"}"u8];
        var loneSurrogate = Encoding.UTF8.GetBytes("{\"name\":\"" + backslash + "uD800\"}");
        var nestedKey = Encoding.UTF8.GetBytes("{\"a\":[{\"" + backslash + "uDC00\":1}]}");

        foreach (var bytes in new[] { invalidUtf8, loneSurrogate, nestedKey })
        {
            Assert.Null(CoworkUser.Json.ParseObject(bytes, out var error));
            Assert.Equal(":invalid_string", error);
        }
        Assert.NotNull(CoworkUser.Json.ParseObject("{\"name\":\"김코워크\"}"u8.ToArray(), out _));
    }

    [Fact]
    public void Identity_command_hash_input_identifies_the_command_regardless_of_timestamp_form()
    {
        var original = IdentityCommandContract.CanonicalPayload(IdentityCommandContract.Parse(Json(IdentityCommand), "7")!);
        var sameInstant = IdentityCommand.Replace("2026-10-18T10:00:00.120+09:00", "2026-10-18T01:00:00.12Z");
        Assert.Equal(original, IdentityCommandContract.CanonicalPayload(IdentityCommandContract.Parse(Json(sameInstant), "7")!));
        Assert.Contains("\"occurredAt\":\"2026-10-18T01:00:00.120000Z\"", original);

        var renamed = IdentityCommand.Replace("김코워크", "박코워크");
        Assert.NotEqual(original, IdentityCommandContract.CanonicalPayload(IdentityCommandContract.Parse(Json(renamed), "7")!));
    }

    [Theory]
    [InlineData("8", IdentityCommand)]
    [InlineData("7", """{"schemaVersion":1}""")]
    public void Identity_command_rejects_mismatched_keys_and_shapes(string key, string payload) =>
        Assert.Null(IdentityCommandContract.Parse(Json(payload), key));

    [Fact]
    public void Identity_command_rejects_non_canonical_operation_ids()
    {
        var upper = IdentityCommand.Replace("0b0f4c8e-6f0e-4a8e-9d3b-1f0c2a9b7c11", "0B0F4C8E-6F0E-4A8E-9D3B-1F0C2A9B7C11");
        Assert.Null(IdentityCommandContract.Parse(Json(upper), "7"));
        Assert.Null(IdentityCommandContract.CorrelationOperationId(Json(upper)));
        Assert.Equal("0b0f4c8e-6f0e-4a8e-9d3b-1f0c2a9b7c11", IdentityCommandContract.CorrelationOperationId(Json(IdentityCommand)));
    }

    const string StudentUpdated = """
        {"event_type":"student.updated","event_id":"e-1","event_index":0,"occurred_at":"2026-05-01T00:00:00Z",
         "email":"kim@gsm.hs.kr","name":"김코워크","sex":"MAN","student_role":"GENERAL_STUDENT","student_number":2105,
         "major":null,"specialty":null,"github_id":null,"datagsm_student_id":42}
        """;

    [Fact]
    public void User_sync_contract_requires_exact_fields_and_matching_key()
    {
        Assert.True(UserSyncContract.Valid(Json(StudentUpdated), "42"));
        Assert.False(UserSyncContract.Valid(Json(StudentUpdated), "43"));
        Assert.False(UserSyncContract.Valid(Json(StudentUpdated.Replace("\"major\":null,", "")), "42"));
        Assert.False(UserSyncContract.Valid(Json(StudentUpdated.Replace("\"name\":\"김코워크\"", "\"name\":\" \"")), "42"));
    }

    [Fact]
    public void Barrier_markers_are_validated_against_their_partition_and_source()
    {
        var marker = Json("""{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"team.member.event","partition":1,"snapshotId":"e71bf570-06e2-443c-9a9c-c0cdd666ba2d","occurredAt":"2026-05-01T00:00:00Z","source":"cowork-team"}""");
        var key = ProjectionBarrier.Key(1);
        Assert.Equal("e71bf570-06e2-443c-9a9c-c0cdd666ba2d", ProjectionBarrier.Parse(marker, key, "team.member.event", 1, "cowork-team")!.SnapshotId);
        Assert.Null(ProjectionBarrier.Parse(Json("""{"eventType":"UPSERT"}"""), "1:2", "team.member.event", 1, "cowork-team"));
        Assert.Throws<InvalidRecord>(() => ProjectionBarrier.Parse(marker, ProjectionBarrier.Key(0), "team.member.event", 1, "cowork-team"));
        Assert.Throws<InvalidRecord>(() => ProjectionBarrier.Parse(marker, key, "team.member.event", 1, "cowork-authorization"));
    }

    [Fact]
    public void Projection_events_parse_camel_or_snake_case()
    {
        var presence = PresenceProjection.Parse(Json("""{"event_type":"STATUS_CHANGED","user_id":"7","status":"online","occurredAt":"2026-05-01T09:00:00+09:00"}"""));
        Assert.Equal(new PresenceProjection.PresenceEvent(7, "online", new DateTime(2026, 5, 1, 0, 0, 0, DateTimeKind.Utc)), presence);
        Assert.Throws<InvalidRecord>(() => PresenceProjection.Parse(Json("""{"eventType":"STATUS_CHANGED","userId":7,"status":"away","occurredAt":"2026-05-01T00:00:00Z"}""")));

        var member = TeamMembershipProjection.Parse(Json("""{"eventType":"DELETE","teamId":11,"userId":7,"role":"MEMBER","teamName":"cowork","occurredAt":"2026-05-01T00:00:00Z"}"""));
        Assert.Equal("DELETE", member.EventType);
        Assert.Throws<InvalidRecord>(() => TeamMembershipProjection.Parse(Json("""{"eventType":"UPSERT","teamId":11,"userId":7,"role":"MEMBER","teamName":"","occurredAt":"2026-05-01T00:00:00Z"}""")));
    }
}

public class ProfileEventTests
{
    [Fact]
    public void Event_time_strictly_increases_with_the_version()
    {
        var current = new DateTime(2026, 5, 1, 0, 0, 0, DateTimeKind.Utc);
        Assert.Equal((current.AddTicks(10), 4UL), ProfileEventPublisher.NextProfileEventState(current, 3, [current.AddSeconds(-1), null]));
        Assert.Equal((current.AddSeconds(5), 1UL), ProfileEventPublisher.NextProfileEventState(null, 0, [current.AddSeconds(5)]));
        Assert.Throws<ArgumentException>(() => ProfileEventPublisher.NextProfileEventState(current, ulong.MaxValue, []));
    }

    [Fact]
    public void Upsert_payload_uses_the_persisted_version()
    {
        var at = new DateTime(2026, 5, 1, 0, 0, 0, DateTimeKind.Utc).AddTicks(1_234_560);
        Assert.Equal(
            """{"eventType":"UPSERT","userId":7,"name":"김코워크","nickname":null,"githubId":"octo","version":2,"occurredAt":"2026-05-01T00:00:00.123456Z"}""",
            ProfileEventPublisher.UpsertPayload(new ProfileEventPublisher.ProfileState(7, "김코워크", null, "octo", 2, at)));
        Assert.Throws<ArgumentException>(() => ProfileEventPublisher.UpsertPayload(new ProfileEventPublisher.ProfileState(7, "kim", null, null, 0, at)));
    }
}
