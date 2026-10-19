using CoworkUser.Platform;
using Dapper;

namespace CoworkUser.Kafka.Commands;

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
