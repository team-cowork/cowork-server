using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Dapper;
using MySqlConnector;

namespace CoworkUser.Kafka;

/// <summary>A failure that must leave the Kafka offset uncommitted so the record is retried.</summary>
public sealed class TransientSyncError(string message, Exception? inner = null) : Exception(message, inner);

public sealed record KafkaRecord(string Topic, int Partition, long Offset, byte[]? Key, byte[]? Value)
{
    public string? KeyText => Key is null ? null : Encoding.UTF8.GetString(Key);
}

/// <summary>Stores records an owner command consumer cannot apply, so the partition keeps moving.</summary>
public static class ActionQuarantine
{
    const string IdentityErrorCode = "INVALID_USER_IDENTITY_COMMAND";
    const string IdentityErrorMessage = "cowork-user rejected an invalid identity command";

    public static async Task QuarantineAsync(string consumerGroup, KafkaRecord record, string reason, string? operationId = null, string? resultTopic = null)
    {
        try
        {
            await App.TransactionAsync(async (connection, transaction) =>
            {
                await connection.ExecuteAsync("""
                    INSERT INTO tb_kafka_action_quarantine
                        (consumer_group, topic_name, partition_id, record_offset, record_key, payload, reason)
                    VALUES (@consumerGroup, @Topic, @Partition, @Offset, @Key, @payload, @reason)
                    ON DUPLICATE KEY UPDATE
                        record_key = VALUES(record_key),
                        payload = VALUES(payload),
                        reason = VALUES(reason),
                        updated_at = CURRENT_TIMESTAMP(6)
                    """, new { consumerGroup, record.Topic, record.Partition, record.Offset, Key = record.Key ?? [], payload = record.Value ?? [], reason = Truncate(reason) }, transaction);

                if (operationId is null || resultTopic is null) return 0;

                var identity = new { consumerGroup, record.Topic, record.Partition, record.Offset };
                var resolutions = (await connection.QueryAsync<string?>("""
                    SELECT CAST(terminal_result_payload AS CHAR)
                    FROM tb_kafka_action_quarantine
                    WHERE consumer_group = @consumerGroup AND topic_name = @Topic AND partition_id = @Partition AND record_offset = @Offset
                    FOR UPDATE
                    """, identity, transaction)).ToList();
                if (resolutions.Count != 1) throw new InvalidOperationException("invalid action quarantine resolution");
                if (resolutions[0] is not null) return 0;

                // The FAILED result is published once per quarantined record.
                var payload = IdentityCommand.ResultPayload(operationId, "FAILED", null, IdentityErrorCode, IdentityErrorMessage);
                await connection.ExecuteAsync(
                    "INSERT INTO tb_kafka_outbox (topic, event_key, payload) VALUES (@resultTopic, @operationId, @payload)",
                    new { resultTopic, operationId, payload }, transaction);
                var updated = await connection.ExecuteAsync("""
                    UPDATE tb_kafka_action_quarantine
                    SET terminal_operation_id = @operationId, terminal_result_payload = @payload, updated_at = CURRENT_TIMESTAMP(6)
                    WHERE consumer_group = @consumerGroup AND topic_name = @Topic AND partition_id = @Partition AND record_offset = @Offset
                      AND terminal_result_payload IS NULL
                    """, new { operationId, payload, consumerGroup, record.Topic, record.Partition, record.Offset }, transaction);
                if (updated != 1) throw new InvalidOperationException("identity failure resolution conflict");
                return 0;
            });
        }
        catch (Exception exception)
        {
            throw new TransientSyncError($"action quarantine storage failure: {exception.Message}", exception);
        }
    }

    internal static string Truncate(string reason) => reason.Length > 1_000 ? reason[..1_000] : reason;
}

/// <summary>Consumes user.data.sync (DataGSM student.updated) events.</summary>
public static class SyncHandler
{
    public static async Task HandleAsync(string consumerGroup, KafkaRecord record, ILogger logger)
    {
        var payload = Json.ParseObject(record.Value, out var decodeError);
        if (payload is null)
        {
            await Quarantine(decodeError!);
            return;
        }
        if (!UserSyncContract.Valid(payload.Value, record.KeyText))
        {
            await Quarantine(":invalid_user_sync_contract");
            return;
        }

        Accounts.StudentEventResult result;
        try
        {
            result = await Accounts.ApplyStudentEventAsync(payload.Value);
        }
        catch (FieldErrors errors)
        {
            await Quarantine(errors.Message);
            return;
        }
        catch (Exception exception)
        {
            throw new TransientSyncError($"kafka user sync transient failure without offset commit: {exception.Message}", exception);
        }

        if (result == Accounts.StudentEventResult.Updated)
            logger.LogInformation("Processed Kafka user sync event topic={Topic} partition={Partition} offset={Offset} key={Key}",
                record.Topic, record.Partition, record.Offset, record.KeyText);
        else
            logger.LogWarning("Skipped Kafka user sync event topic={Topic} partition={Partition} offset={Offset} reason={Reason}",
                record.Topic, record.Partition, record.Offset, result == Accounts.StudentEventResult.AccountNotFound ? ":account_not_found" : ":stale_student_event");

        async Task Quarantine(string reason)
        {
            await ActionQuarantine.QuarantineAsync(consumerGroup, record, reason);
            logger.LogWarning("Quarantined invalid Kafka user sync action topic={Topic} partition={Partition} offset={Offset} reason={Reason}",
                record.Topic, record.Partition, record.Offset, reason);
        }
    }
}

/// <summary>Applies authorization's user.identity.command with an idempotent inbox and a result outbox.</summary>
public static class IdentityCommand
{
    const string ResultErrorCode = "USER_IDENTITY_REJECTED";
    const string ResultErrorMessage = "cowork-user could not commit the requested identity";

    sealed class ConflictingReuse() : Exception(":conflicting_identity_command_reuse");

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
                // Rows written under hash version 1 (the previous runtime's serialization) cannot be recomputed, so a
                // retry of one is matched by its ids. Drop this once no version 1 rows remain.
                var hashComparable = stored.HashVersion == IdentityCommandContract.HashVersion;
                if (stored.OperationId != command.OperationId || stored.IdempotencyKey != command.IdempotencyKey
                    || stored.UserId != command.UserId || (hashComparable && stored.CommandHash != commandHash))
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

    internal static string Hash(string value) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
}
