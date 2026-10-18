using System.Collections.Concurrent;
using System.Text;
using Confluent.Kafka;

namespace CoworkUser.Kafka;

/// <summary>Synchronous publishing for the transactional outbox relay.</summary>
public sealed class Producer : IDisposable
{
    static readonly TimeSpan MetadataTimeout = TimeSpan.FromSeconds(10);
    const long PartitionCountTtlMs = 30_000;

    readonly IProducer<string, byte[]> producer;
    readonly IAdminClient admin;
    readonly ConcurrentDictionary<string, (int Count, long ExpiresAt)> partitionCounts = new();

    public Producer(AppConfig config)
    {
        producer = new ProducerBuilder<string, byte[]>(new ProducerConfig
        {
            BootstrapServers = config.KafkaBootstrapServers,
            Acks = Acks.All,
            MessageTimeoutMs = 10_000,
            RequestTimeoutMs = 5_000,
            // Records are published one at a time while outbox rows stay locked.
            LingerMs = 0,
        }).Build();
        admin = new DependentAdminClientBuilder(producer.Handle).Build();
    }

    /// <summary>Publishes to the partition brod's hash partitioner chose, or an explicit one for snapshot markers.</summary>
    public async Task PublishAsync(string topic, string key, string payload, int? partition)
    {
        var target = partition ?? (int)(Phash2(Encoding.UTF8.GetBytes(key)) % (uint)CachedPartitionCount(topic));
        await producer.ProduceAsync(new TopicPartition(topic, new Partition(target)),
            new Message<string, byte[]> { Key = key, Value = Encoding.UTF8.GetBytes(payload) });
    }

    int CachedPartitionCount(string topic)
    {
        if (partitionCounts.TryGetValue(topic, out var cached) && Environment.TickCount64 < cached.ExpiresAt) return cached.Count;
        var count = PartitionCount(topic);
        partitionCounts[topic] = (count, Environment.TickCount64 + PartitionCountTtlMs);
        return count;
    }

    public int PartitionCount(string topic)
    {
        var metadata = admin.GetMetadata(topic, MetadataTimeout).Topics.Single(t => t.Topic == topic);
        if (metadata.Error.IsError) throw new KafkaException(metadata.Error);
        return metadata.Partitions.Count;
    }

    /// <summary>erlang:phash2/1 of a binary: OTP's Jenkins block hash seeded for binaries, in 2^27.</summary>
    internal static uint Phash2(ReadOnlySpan<byte> key)
    {
        uint a = 0x9e3779b9, b = 0x9e3779b9, c = 0x08d12e65;
        if (key.IsEmpty) return c & ((1u << 27) - 1);

        var k = key;
        while (k.Length >= 12)
        {
            a += BitConverter.ToUInt32(k[..4]);
            b += BitConverter.ToUInt32(k[4..8]);
            c += BitConverter.ToUInt32(k[8..12]);
            Mix(ref a, ref b, ref c);
            k = k[12..];
        }

        c += (uint)key.Length;
        for (var i = k.Length - 1; i >= 0; i--)
        {
            // The first byte of c is reserved for the length.
            if (i >= 8) c += (uint)k[i] << (8 * (i - 7));
            else if (i >= 4) b += (uint)k[i] << (8 * (i - 4));
            else a += (uint)k[i] << (8 * i);
        }
        Mix(ref a, ref b, ref c);
        return c & ((1u << 27) - 1);
    }

    static void Mix(ref uint a, ref uint b, ref uint c)
    {
        a -= b; a -= c; a ^= c >> 13;
        b -= c; b -= a; b ^= a << 8;
        c -= a; c -= b; c ^= b >> 13;
        a -= b; a -= c; a ^= c >> 12;
        b -= c; b -= a; b ^= a << 16;
        c -= a; c -= b; c ^= b >> 5;
        a -= b; a -= c; a ^= c >> 3;
        b -= c; b -= a; b ^= a << 10;
        c -= a; c -= b; c ^= b >> 15;
    }

    public void Dispose()
    {
        admin.Dispose();
        producer.Flush(TimeSpan.FromSeconds(5));
        producer.Dispose();
    }
}
