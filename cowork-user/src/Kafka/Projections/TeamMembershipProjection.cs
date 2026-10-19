using System.Text.Json;
using CoworkUser.Platform;
using Dapper;
using MySqlConnector;

namespace CoworkUser.Kafka.Projections;

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
