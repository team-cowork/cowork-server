using CoworkUser.Kafka.Commands;
using Dapper;
using MySqlConnector;

namespace CoworkUser.Kafka.Projections;

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
