using System.Text.Json;
using Dapper;
using MySqlConnector;

namespace CoworkUser.Kafka;

public static class PresenceProjection
{
    public sealed record PresenceEvent(long UserId, string Status, DateTime OccurredAt);

    public static PresenceEvent Parse(JsonElement payload)
    {
        var eventType = ContractFields.String(payload.GetEither("eventType", "event_type"));
        if (eventType != "STATUS_CHANGED") throw new InvalidRecord(":invalid_event_type");
        var userId = EventParser.PositiveInteger(payload, "userId", "user_id");
        var status = payload.GetString("status");
        if (status is not ("online" or "offline")) throw new InvalidRecord(":invalid_status");
        return new PresenceEvent(userId, status, EventParser.UtcDateTime(payload, "occurredAt", "occurred_at"));
    }

    /// <summary>Newer events win; at the same instant, offline wins over online.</summary>
    public static async Task ApplyAsync(MySqlConnection connection, MySqlTransaction transaction, PresenceEvent presence)
    {
        await connection.ExecuteAsync("""
            INSERT INTO tb_user_presence_projections (user_id, status, event_occurred_at)
            VALUES (@UserId, @Status, @OccurredAt)
            ON DUPLICATE KEY UPDATE
                status = IF(
                    VALUES(event_occurred_at) > event_occurred_at
                    OR (
                        VALUES(event_occurred_at) = event_occurred_at
                        AND VALUES(status) = 'offline'
                        AND status = 'online'
                    ),
                    VALUES(status),
                    status
                ),
                event_occurred_at = GREATEST(event_occurred_at, VALUES(event_occurred_at))
            """, presence, transaction);
        await ApplyLatestToAccountAsync(connection, transaction, presence.UserId);
    }

    /// <summary>
    /// Clears the projected presence and the presence copied onto accounts; user-owned status fields
    /// (custom_status, status_message, status_expires_at) stay.
    /// </summary>
    public static async Task ResetAsync(MySqlConnection connection, MySqlTransaction transaction)
    {
        await connection.ExecuteAsync("DELETE FROM tb_user_presence_projections", transaction: transaction);
        await connection.ExecuteAsync(
            "UPDATE tb_accounts SET status = 'offline', presence_updated_at = '1970-01-01 00:00:00.000000'", transaction: transaction);
    }

    public static Task ApplyLatestToAccountAsync(MySqlConnection connection, MySqlTransaction transaction, long userId) =>
        connection.ExecuteAsync("""
            UPDATE tb_accounts AS account
            JOIN tb_user_presence_projections AS presence ON presence.user_id = account.id
            SET account.status = presence.status,
                account.presence_updated_at = presence.event_occurred_at,
                account.last_modified_by = presence.user_id
            WHERE account.id = @userId
              AND (
                  presence.event_occurred_at > account.presence_updated_at
                  OR (
                      presence.event_occurred_at = account.presence_updated_at
                      AND presence.status = 'offline'
                      AND account.status = 'online'
                  )
              )
            """, new { userId }, transaction);
}

public static class TeamMembershipProjection
{
    public sealed record MemberEvent(string EventType, long TeamId, long UserId, string Role, string TeamName, DateTime OccurredAt);

    public static MemberEvent Parse(JsonElement payload)
    {
        var eventType = ContractFields.String(payload.GetEither("eventType", "event_type"));
        if (eventType is not ("UPSERT" or "DELETE")) throw new InvalidRecord(":invalid_event_type");
        var teamId = EventParser.PositiveInteger(payload, "teamId", "team_id");
        var userId = EventParser.PositiveInteger(payload, "userId", "user_id");
        var role = payload.GetString("role");
        if (role is not ("OWNER" or "ADMIN" or "MEMBER")) throw new InvalidRecord(":invalid_role");
        if (ContractFields.String(payload.GetEither("teamName", "team_name")) is not { Length: > 0 } teamName)
            throw new InvalidRecord(":invalid_team_name");
        return new MemberEvent(eventType, teamId, userId, role, teamName, EventParser.UtcDateTime(payload, "occurredAt", "occurred_at"));
    }

    public static Task ResetAsync(MySqlConnection connection, MySqlTransaction transaction) =>
        connection.ExecuteAsync("DELETE FROM tb_team_member_projections", transaction: transaction);

    public static Task ApplyAsync(MySqlConnection connection, MySqlTransaction transaction, MemberEvent member) =>
        connection.ExecuteAsync("""
            INSERT INTO tb_team_member_projections
                (team_id, user_id, role, team_name, active, event_occurred_at)
            VALUES (@TeamId, @UserId, @Role, @TeamName, @active, @OccurredAt)
            ON DUPLICATE KEY UPDATE
                role = IF(VALUES(event_occurred_at) >= event_occurred_at, VALUES(role), role),
                team_name = IF(VALUES(event_occurred_at) >= event_occurred_at, VALUES(team_name), team_name),
                active = IF(
                    VALUES(event_occurred_at) > event_occurred_at,
                    VALUES(active),
                    IF(VALUES(event_occurred_at) = event_occurred_at, active AND VALUES(active), active)
                ),
                event_occurred_at = GREATEST(event_occurred_at, VALUES(event_occurred_at))
            """, new { member.TeamId, member.UserId, member.Role, member.TeamName, active = member.EventType == "UPSERT", member.OccurredAt }, transaction);

    /// <summary>Active member IDs of a team, or none when the requester is not an active member.</summary>
    public static async Task<IReadOnlyList<long>> MemberIdsForRequesterAsync(long teamId, long requesterUserId)
    {
        await using var connection = await App.OpenAsync();
        return (await connection.QueryAsync<long>("""
            SELECT member.user_id
            FROM tb_team_member_projections AS member
            WHERE member.team_id = @teamId
              AND member.active = TRUE
              AND EXISTS (
                  SELECT 1
                  FROM tb_team_member_projections AS requester
                  WHERE requester.team_id = member.team_id
                    AND requester.user_id = @requesterUserId
                    AND requester.active = TRUE
              )
            ORDER BY member.user_id
            """, new { teamId, requesterUserId })).ToList();
    }
}

/// <summary>
/// Applies one projection record, its quarantine and its checkpoint under the partition replay
/// lease, all inside the caller's batch transaction.
/// </summary>
public static class ProjectionProcessor
{
    public const int TransactionBatchSize = 20;

    public static async Task ProcessAsync(MySqlConnection connection, MySqlTransaction transaction, ProjectionCheckpoint.Lease lease,
        KafkaRecord record, Func<Task> apply, Action stateGapDetected, ILogger logger, string eventName)
    {
        await ProjectionCheckpoint.LockLeaseAsync(connection, transaction, lease);
        string? invalid = null;
        try
        {
            await apply();
        }
        catch (InvalidRecord rejected)
        {
            invalid = rejected.Message;
        }

        if (invalid is not null)
        {
            await connection.ExecuteAsync("""
                INSERT INTO tb_kafka_projection_quarantine
                    (consumer_group, topic_name, partition_id, record_offset, record_key, payload, reason)
                VALUES (@ConsumerGroup, @Topic, @Partition, @Offset, @Key, @payload, @reason)
                ON DUPLICATE KEY UPDATE
                    record_key = VALUES(record_key),
                    payload = VALUES(payload),
                    reason = VALUES(reason),
                    updated_at = CURRENT_TIMESTAMP(6)
                """, new { lease.ConsumerGroup, lease.Topic, lease.Partition, record.Offset, Key = record.Key ?? [], payload = record.Value ?? [], reason = ActionQuarantine.Truncate(invalid) }, transaction);
            await ProjectionCheckpoint.MarkInvalidRecordAsync(connection, transaction, lease, record.Offset);
        }

        await ProjectionCheckpoint.AdvanceAsync(connection, transaction, lease, record.Offset + 1);

        if (invalid is not null)
        {
            stateGapDetected();
            logger.LogWarning("Discarding invalid {EventName} topic={Topic} partition={Partition} offset={Offset} reason={Reason}",
                eventName, record.Topic, record.Partition, record.Offset, invalid);
        }
    }
}
