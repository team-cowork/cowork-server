using Confluent.Kafka;
using Confluent.Kafka.Admin;
using CoworkUser.Config;

namespace CoworkUser.Kafka;

/// <summary>Broker queries for replay validation, serialized on one standalone consumer.</summary>
public sealed class KafkaInspector : IDisposable
{
    static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);

    public sealed class OffsetMissing() : Exception(":offset_out_of_range");

    readonly Lock gate = new();
    readonly IConsumer<string, byte[]> consumer;
    readonly IAdminClient admin;

    public KafkaInspector(AppConfig config)
    {
        consumer = new ConsumerBuilder<string, byte[]>(new ConsumerConfig
        {
            BootstrapServers = config.KafkaBootstrapServers,
            GroupId = "cowork-user.projection-inspector",
            EnableAutoCommit = false,
            AutoOffsetReset = AutoOffsetReset.Error,
        }).SetKeyDeserializer(Deserializers.Utf8).Build();
        admin = new DependentAdminClientBuilder(consumer.Handle).Build();
    }

    public int PartitionCount(string topic)
    {
        lock (gate)
        {
            var metadata = admin.GetMetadata(topic, Timeout).Topics.Single(t => t.Topic == topic);
            if (metadata.Error.IsError) throw new KafkaException(metadata.Error);
            return metadata.Partitions.Count;
        }
    }

    /// <summary>The broker's topic UUID, or null when the broker does not report one.</summary>
    public string? TopicId(string topic)
    {
        lock (gate)
        {
            var result = admin.DescribeTopicsAsync(TopicCollection.OfTopicNames([topic]), new DescribeTopicsOptions { RequestTimeout = Timeout })
                .GetAwaiter().GetResult();
            var id = result.TopicDescriptions.Single().TopicId;
            return id.MostSignificantBits == 0 && id.LeastSignificantBits == 0 ? null : id.ToString();
        }
    }

    public (long Low, long High) Watermarks(string topic, int partition)
    {
        lock (gate)
        {
            var offsets = consumer.QueryWatermarkOffsets(new TopicPartition(topic, partition), Timeout);
            return (offsets.Low.Value, offsets.High.Value);
        }
    }

    /// <summary>The record stored at exactly <paramref name="offset"/>, or null when it is not readable.</summary>
    public ConsumeResult<string, byte[]>? FetchAt(string topic, int partition, long offset)
    {
        lock (gate)
        {
            consumer.Assign(new TopicPartitionOffset(topic, partition, offset));
            try
            {
                var result = consumer.Consume(TimeSpan.FromSeconds(2));
                return result is null || result.IsPartitionEOF ? throw new TimeoutException("projection marker fetch timed out")
                    : result.Offset.Value == offset ? result : null;
            }
            catch (ConsumeException error) when (error.Error.Code is ErrorCode.Local_AutoOffsetReset or ErrorCode.OffsetOutOfRange)
            {
                throw new OffsetMissing();
            }
            finally
            {
                consumer.Unassign();
            }
        }
    }

    public void Dispose()
    {
        admin.Dispose();
        consumer.Close();
        consumer.Dispose();
    }
}
