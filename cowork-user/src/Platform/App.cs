using CoworkUser.Config;
using MySqlConnector;

namespace CoworkUser.Platform;

/// <summary>Process-wide services, mirroring the single-node runtime the service was built around.</summary>
public static class App
{
    public static AppConfig Config { get; set; } = null!;
    public static MySqlDataSource Db { get; set; } = null!;
    public static ILoggerFactory Logs { get; set; } = null!;

    public static async Task<MySqlConnection> OpenAsync(CancellationToken cancellationToken = default) =>
        await Db.OpenConnectionAsync(cancellationToken);

    public static async Task<T?> FirstOrNullAsync<T>(this Task<IEnumerable<T>> query) where T : struct
    {
        foreach (var row in await query) return row;
        return null;
    }

    public static async Task<T> TransactionAsync<T>(Func<MySqlConnection, MySqlTransaction, Task<T>> work, CancellationToken cancellationToken = default)
    {
        await using var connection = await OpenAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var result = await work(connection, transaction);
        await transaction.CommitAsync(cancellationToken);
        return result;
    }
}
