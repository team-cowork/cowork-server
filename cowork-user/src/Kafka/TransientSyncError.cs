namespace CoworkUser.Kafka;

/// <summary>A failure that must leave the Kafka offset uncommitted so the record is retried.</summary>
public sealed class TransientSyncError(string message, Exception? inner = null) : Exception(message, inner);
