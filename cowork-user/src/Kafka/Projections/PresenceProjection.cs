using System.Text.Json;
using CoworkUser.Platform;
using Dapper;
using MySqlConnector;

namespace CoworkUser.Kafka.Projections;

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
