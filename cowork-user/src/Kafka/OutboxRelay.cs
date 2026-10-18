using Dapper;

namespace CoworkUser.Kafka;

/// <summary>Publishes tb_kafka_outbox rows in id order; one replica relays at a time.</summary>
public sealed class OutboxRelay(Producer producer, ILogger<OutboxRelay> logger) : BackgroundService
{
    const string LockName = "cowork-user:kafka-outbox";
    const int BatchSize = 100;
    const int ErrorMaxCharacters = 8_000;

    internal sealed record Record(long Id, string Topic, string EventKey, int? PartitionId, string Payload);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await RelayOnceAsync(stoppingToken);
            }
            catch (Exception exception) when (!stoppingToken.IsCancellationRequested)
            {
                logger.LogError("User profile outbox relay failed: {Reason}", exception.Message);
            }

            try
            {
                await Task.Delay(250, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    async Task RelayOnceAsync(CancellationToken cancellationToken)
    {
        await using var connection = await App.OpenAsync(cancellationToken);
        if (await connection.ExecuteScalarAsync<long?>("SELECT GET_LOCK(@LockName, 0)", new { LockName }) != 1) return;

        try
        {
            await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
            var records = (await connection.QueryAsync<Record>("""
                SELECT id AS Id, topic AS Topic, event_key AS EventKey, partition_id AS PartitionId, CAST(payload AS CHAR) AS Payload
                FROM tb_kafka_outbox
                ORDER BY id ASC
                LIMIT @BatchSize
                FOR UPDATE
                """, new { BatchSize }, transaction)).ToList();

            var failure = await DispatchAsync(records,
                record => producer.PublishAsync(record.Topic, record.EventKey, record.Payload, record.PartitionId),
                async record =>
                {
                    var deleted = await connection.ExecuteAsync("DELETE FROM tb_kafka_outbox WHERE id = @Id", new { record.Id }, transaction);
                    if (deleted != 1) throw new InvalidOperationException($"unexpected delete count {deleted}");
                },
                async (record, error) =>
                {
                    try
                    {
                        await connection.ExecuteAsync(
                            "UPDATE tb_kafka_outbox SET attempts = attempts + 1, last_error = @error WHERE id = @Id",
                            new { error = error.Length > ErrorMaxCharacters ? error[..ErrorMaxCharacters] : error, record.Id }, transaction);
                    }
                    catch (Exception exception)
                    {
                        logger.LogWarning("Failed to record outbox error: {Reason}", exception.Message);
                    }
                });

            await transaction.CommitAsync(cancellationToken);
            if (failure is not null) logger.LogWarning("User profile outbox paused: {Reason}", failure);
        }
        finally
        {
            if (await connection.ExecuteScalarAsync<long?>("SELECT RELEASE_LOCK(@LockName)", new { LockName }) != 1)
                logger.LogWarning("User profile outbox lock was not held");
        }
    }

    /// <summary>Publishes records in order, stopping at the first failure so ordering is preserved.</summary>
    internal static async Task<string?> DispatchAsync(
        IEnumerable<Record> records, Func<Record, Task> publish, Func<Record, Task> acknowledge, Func<Record, string, Task> markFailure)
    {
        foreach (var record in records)
        {
            try
            {
                await publish(record);
            }
            catch (Exception exception)
            {
                await markFailure(record, exception.Message);
                return $"publish {record.Id}: {exception.Message}";
            }

            try
            {
                await acknowledge(record);
            }
            catch (Exception exception)
            {
                return $"acknowledge {record.Id}: {exception.Message}";
            }
        }
        return null;
    }
}
