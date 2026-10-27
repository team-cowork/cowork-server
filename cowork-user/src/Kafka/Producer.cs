using System.Text;
using Confluent.Kafka;
using CoworkUser.Config;

namespace CoworkUser.Kafka;

/// <summary>Synchronous publishing for the transactional outbox relay.</summary>
public sealed class Producer : IDisposable
{
    static readonly TimeSpan MetadataTimeout = TimeSpan.FromSeconds(10);

    readonly IProducer<string, byte[]> producer;
    readonly IAdminClient admin;

    public Producer(AppConfig config)
    {
        producer = new ProducerBuilder<string, byte[]>(new ProducerConfig(config.KafkaSecurity.CreateClientConfig())
        {
            BootstrapServers = config.KafkaBootstrapServers,
            Acks = Acks.All,
            MessageTimeoutMs = 10_000,
            RequestTimeoutMs = 5_000,
            // Records are published one at a time while outbox rows stay locked.
            LingerMs = 0,
            // The Java client's key hash, so a key lands on the same partition as with other Kafka clients.
            Partitioner = Partitioner.Murmur2Random,
        }).Build();
        admin = new DependentAdminClientBuilder(producer.Handle).Build();
    }

    /// <summary>Publishes to an explicit partition (snapshot markers), or to the one the partitioner picks for the key.</summary>
    public async Task PublishAsync(string topic, string key, string payload, int? partition)
    {
        var message = new Message<string, byte[]> { Key = key, Value = Encoding.UTF8.GetBytes(payload) };
        if (partition is { } target) await producer.ProduceAsync(new TopicPartition(topic, new Partition(target)), message);
        else await producer.ProduceAsync(topic, message);
    }

    public int PartitionCount(string topic)
    {
        var metadata = admin.GetMetadata(topic, MetadataTimeout).Topics.Single(t => t.Topic == topic);
        if (metadata.Error.IsError) throw new KafkaException(metadata.Error);
        return metadata.Partitions.Count;
    }

    public void Dispose()
    {
        admin.Dispose();
        producer.Flush(TimeSpan.FromSeconds(5));
        producer.Dispose();
    }
}
