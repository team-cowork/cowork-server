using System.Text;

namespace CoworkUser.Kafka;

public sealed record KafkaRecord(string Topic, int Partition, long Offset, byte[]? Key, byte[]? Value)
{
    public string? KeyText => Key is null ? null : Encoding.UTF8.GetString(Key);
}
