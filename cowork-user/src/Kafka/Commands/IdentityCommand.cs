using System.Security.Cryptography;
using System.Text;
using CoworkUser.Platform;
using CoworkUser.Users;
using Dapper;
using MySqlConnector;

namespace CoworkUser.Kafka.Commands;

/// <summary>Applies authorization's user.identity.command with an idempotent inbox and a result outbox.</summary>
public static class IdentityCommand
{
    const string ResultErrorCode = "USER_IDENTITY_REJECTED";
    const string ResultErrorMessage = "cowork-user could not commit the requested identity";

    sealed class ConflictingReuse(string reason = ":conflicting_identity_command_reuse") : Exception(reason);

    public static async Task HandleAsync(string consumerGroup, string resultTopic, KafkaRecord record, ILogger logger)
    {
        var payload = Json.ParseObject(record.Value, out var decodeError);
        if (payload is null)
        {
            await Quarantine(null, decodeError!);
            return;
        }

        var command = IdentityCommandContract.Parse(payload.Value, record.KeyText);
        if (command is null)
        {
            await Quarantine(IdentityCommandContract.CorrelationOperationId(payload.Value), ":invalid_user_identity_command");
            return;
        }

        try
        {
            await ProcessAsync(command, resultTopic);
        }
        catch (ConflictingReuse conflict)
        {
            await Quarantine(command.OperationId, conflict.Message);
            return;
        }
        catch (Exception exception)
        {
            throw new TransientSyncError($"user identity command transient failure without offset commit: {exception.Message}", exception);
        }

        logger.LogInformation("Committed user identity command topic={Topic} partition={Partition} offset={Offset}",
            record.Topic, record.Partition, record.Offset);

        async Task Quarantine(string? operationId, string reason)
        {
            await ActionQuarantine.QuarantineAsync(consumerGroup, record, reason, operationId, resultTopic);
            logger.LogWarning("Quarantined invalid user identity command topic={Topic} partition={Partition} offset={Offset} operation_id={OperationId} reason={Reason}",
                record.Topic, record.Partition, record.Offset, operationId, reason);
        }
    }

    static Task ProcessAsync(IdentityCommandContract.Command command, string resultTopic) =>
        App.TransactionAsync(async (connection, transaction) =>
        {
            var commandHash = Hash(IdentityCommandContract.CanonicalPayload(command));
            var inbox = (await connection.QueryAsync<(string OperationId, string IdempotencyKey, long UserId, string CommandHash, long HashVersion, string ResultPayload)>("""
                SELECT operation_id, idempotency_key, user_id, command_hash, CAST(hash_version AS SIGNED), CAST(result_payload AS CHAR)
                FROM tb_user_identity_command_inbox
                WHERE operation_id = @OperationId OR idempotency_key = @IdempotencyKey
                ORDER BY operation_id
                FOR UPDATE
                """, new { command.OperationId, command.IdempotencyKey }, transaction)).ToList();

            if (inbox.Count > 1) throw new ConflictingReuse();
            if (inbox.Count == 1)
            {
                var stored = inbox[0];
                // Every stored row is verified with the rule it was written under; an unknown version is refused.
                if (HashFor(stored.HashVersion, command) is not { } expectedHash) throw new ConflictingReuse(":unsupported_identity_command_hash_version");
                if (stored.OperationId != command.OperationId || stored.IdempotencyKey != command.IdempotencyKey
                    || stored.UserId != command.UserId || stored.CommandHash != expectedHash)
                    throw new ConflictingReuse();
                await EnqueueResultAsync(connection, transaction, resultTopic, stored.OperationId, stored.ResultPayload);
                return 0;
            }

            var applied = await Accounts.ApplyIdentityCommandAsync(connection, transaction, command);
            var status = applied ? "SUCCEEDED" : "FAILED";
            var payload = applied
                ? ResultPayload(command.OperationId, status, command.UserId, null, null)
                : ResultPayload(command.OperationId, status, null, ResultErrorCode, ResultErrorMessage);

            await connection.ExecuteAsync("""
                INSERT INTO tb_user_identity_command_inbox
                    (operation_id, idempotency_key, user_id, command_hash, hash_version, result_status, result_payload, result_hash, processed_at)
                VALUES (@OperationId, @IdempotencyKey, @UserId, @commandHash, @hashVersion, @status, @payload, @resultHash, @processedAt)
                """, new
            {
                command.OperationId, command.IdempotencyKey, command.UserId, commandHash, hashVersion = IdentityCommandContract.HashVersion,
                status, payload, resultHash = Hash(payload), processedAt = Iso8601.UtcNow(),
            }, transaction);
            await EnqueueResultAsync(connection, transaction, resultTopic, command.OperationId, payload);
            return 0;
        });

    static Task EnqueueResultAsync(MySqlConnection connection, MySqlTransaction transaction, string topic, string operationId, string payload) =>
        connection.ExecuteAsync("INSERT INTO tb_kafka_outbox (topic, event_key, payload) VALUES (@topic, @operationId, @payload)",
            new { topic, operationId, payload }, transaction);

    internal static string ResultPayload(string operationId, string status, long? userId, string? errorCode, string? errorMessage)
    {
        var result = new SortedDictionary<string, object?>(StringComparer.Ordinal)
        {
            ["schemaVersion"] = 1,
            ["operationId"] = operationId,
            ["status"] = status,
            ["occurredAt"] = Iso8601.Format(Iso8601.UtcNow()),
        };
        if (userId is not null) result["userId"] = userId;
        if (errorCode is not null) result["error"] = new SortedDictionary<string, string>(StringComparer.Ordinal) { ["code"] = errorCode, ["message"] = errorMessage! };
        return Json.Encode(result);
    }

    /// <summary>The hash of a command under the given version's rule, or null when the version is not supported.</summary>
    internal static string? HashFor(long hashVersion, IdentityCommandContract.Command command) => hashVersion switch
    {
        1 => Hash(IdentityCommandContract.LegacyCanonicalPayload(command)),
        IdentityCommandContract.HashVersion => Hash(IdentityCommandContract.CanonicalPayload(command)),
        _ => null,
    };

    internal static string Hash(string value) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
}
