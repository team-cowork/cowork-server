namespace CoworkUser.Kafka;

/// <summary>A record rejected by its contract; the reason is stored with the quarantined record.</summary>
public sealed class InvalidRecord(string reason) : Exception(reason);
