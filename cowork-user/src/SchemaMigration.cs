using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;
using Dapper;
using MySqlConnector;

namespace CoworkUser;

/// <summary>
/// Applies the bundled MySQL SQL migrations before the application starts.
/// The history table, checksums and named lock match Flyway 12.8.1 on standalone MySQL, so the
/// history written by the previous runtime stays valid. Only integer-versioned plain DDL/DML scripts
/// are supported. MySQL DDL is not transactional: an incomplete migration remains marked failed and
/// an operator must reconcile the schema before repairing its history.
/// </summary>
public static partial class SchemaMigration
{
    const string HistoryTable = "flyway_schema_history";
    const int LockTimeoutSeconds = 60;
    const int BootstrapTimeoutSeconds = 900;
    static readonly string[] StatementKeywords = ["CREATE", "ALTER", "DROP", "RENAME", "TRUNCATE", "INSERT", "UPDATE", "DELETE", "REPLACE"];

    public sealed class Error(string message) : Exception(message);

    internal sealed record Migration(int Version, string Description, string Script, int Checksum, IReadOnlyList<string> Statements);

    sealed record HistoryRow(int InstalledRank, string? Version, string Description, string Type, string Script, int? Checksum, int ExecutionTime, bool Success);

    [GeneratedRegex(@"\AV([1-9][0-9]*)__([a-z][a-z0-9]*(?:_[a-z0-9]+)*)\.sql\z")]
    private static partial Regex FileName();

    [GeneratedRegex(@"'(?:''|\\.|[^'\\])*'|""(?:""""|\\.|[^""\\])*""|`(?:``|[^`])*`|--(?=[\x00-\x20])[^\r\n]*|\#[^\r\n]*|/\*.*?\*/", RegexOptions.Singleline)]
    private static partial Regex LiteralsAndComments();

    public static void Run(string connectionString, ILogger logger)
    {
        var migrations = LoadMigrations(Path.Combine(AppContext.BaseDirectory, "db", "migration"));
        var builder = new MySqlConnectionStringBuilder(connectionString)
        {
            Pooling = false,
            ConnectionTimeout = 15,
            DefaultCommandTimeout = BootstrapTimeoutSeconds,
        };

        try
        {
            using var connection = new MySqlConnection(builder.ConnectionString);
            connection.Open();
            Migrate(connection, migrations, logger);
        }
        catch (Error)
        {
            throw;
        }
        catch (MySqlException error)
        {
            throw new Error($"Schema bootstrap failed (MySQL error {error.Number}); connection and SQL details are hidden");
        }
        catch (Exception)
        {
            throw new Error("Schema bootstrap failed (database or migration resource error); connection and SQL details are hidden");
        }
    }

    internal static List<Migration> LoadMigrations(string directory)
    {
        if (!Directory.Exists(directory)) throw new Error("The release migration directory is unavailable");

        var migrations = Directory.GetFiles(directory).Select(path =>
        {
            var fileName = Path.GetFileName(path);
            var match = FileName().Match(fileName);
            if (!match.Success)
                throw new Error($"Unsupported migration resource: {fileName}; expected V{{n}}__snake_case.sql");
            if (match.Groups[1].Length > 50 || match.Groups[2].Length > 200)
                throw new Error($"Migration filename exceeds Flyway history limits: {fileName}");

            var bytes = File.ReadAllBytes(path);
            if (bytes.AsSpan().StartsWith((byte[])[0xEF, 0xBB, 0xBF])) bytes = bytes[3..];
            string sql;
            try
            {
                sql = new UTF8Encoding(false, true).GetString(bytes);
            }
            catch (DecoderFallbackException)
            {
                throw new Error($"Migration is not valid UTF-8: {fileName}");
            }

            return new Migration(int.Parse(match.Groups[1].Value), match.Groups[2].Value.Replace('_', ' '), fileName,
                Checksum(sql), ValidateSql(sql, fileName));
        }).OrderBy(m => m.Version).ToList();

        if (migrations.Count == 0) throw new Error("No bundled SQL migrations were found");
        if (migrations.Select(m => m.Version).Distinct().Count() != migrations.Count)
            throw new Error("Bundled SQL migrations contain duplicate versions");
        return migrations;
    }

    internal static List<string> ValidateSql(string sql, string fileName)
    {
        if (sql.Contains("${") || Regex.IsMatch(sql, @"/\*(?:!|M!)", RegexOptions.IgnoreCase)
            || Regex.IsMatch(sql, @"^\s*(?:--|\#)\s*flyway\s*:", RegexOptions.IgnoreCase | RegexOptions.Multiline))
            throw new Error($"Migration uses unsupported placeholders or executable directives: {fileName}");

        // Keep offsets so only semicolons outside literals/comments delimit statements,
        // while the SQL sent to MySQL retains its original contents.
        var code = LiteralsAndComments().Replace(sql, token => new string(' ', token.Length));
        var keywords = Regex.Matches(code, @"(?:\A|;)\s*([a-z]+)", RegexOptions.IgnoreCase)
            .Select(m => m.Groups[1].Value.ToUpperInvariant()).ToList();

        if (keywords.Count == 0 || keywords.Any(k => !StatementKeywords.Contains(k))
            || Regex.IsMatch(code, @"\b(?:DELIMITER|SOURCE|DEFINER|PROCEDURE|FUNCTION|TRIGGER)\b", RegexOptions.IgnoreCase)
            || Regex.IsMatch(code, @"\b(?:CREATE|ALTER|DROP)\s+(?:DATABASE|SCHEMA|EVENT)\b", RegexOptions.IgnoreCase)
            || Regex.IsMatch(sql, @"\b(?:GET_LOCK|RELEASE_LOCK|RELEASE_ALL_LOCKS|flyway_schema_history)\b", RegexOptions.IgnoreCase))
            throw new Error($"Migration must contain plain DDL/DML without session, lock or history control: {fileName}");

        var statements = new List<string>();
        var offset = 0;
        foreach (var boundary in Enumerable.Range(0, code.Length).Where(i => code[i] == ';').Append(code.Length))
        {
            if (code[offset..boundary].Trim() != "") statements.Add(sql[offset..boundary]);
            offset = boundary + 1;
        }
        return statements;
    }

    // Flyway: CRC32 over the UTF-8 script lines without line terminators, as a signed int.
    internal static int Checksum(string sql)
    {
        var crc = 0xFFFFFFFFu;
        foreach (var b in Encoding.UTF8.GetBytes(sql.Replace("\r", "").Replace("\n", "")))
        {
            crc ^= b;
            for (var bit = 0; bit < 8; bit++) crc = (crc >> 1) ^ (0xEDB88320u & (uint)-(int)(crc & 1));
        }
        return unchecked((int)~crc);
    }

    // Flyway 12.8.1 MySQLConnection.lock hashes `schema`.`flyway_schema_history` with Java String.hashCode.
    internal static string LockName(string database)
    {
        var hash = 0;
        foreach (var unit in $"`{database}`.`{HistoryTable}`") hash = unchecked(hash * 31 + unit);
        return $"Flyway-{hash}";
    }

    static void Migrate(MySqlConnection connection, List<Migration> migrations, ILogger logger)
    {
        var database = connection.ExecuteScalar<string?>("SELECT DATABASE()");
        if (string.IsNullOrEmpty(database)) throw new Error("A database must be selected before schema bootstrap");

        var lockName = LockName(database);
        switch (connection.ExecuteScalar<long?>("SELECT GET_LOCK(@lockName, @timeout)", new { lockName, timeout = LockTimeoutSeconds }))
        {
            case 1: break;
            case 0: throw new Error($"Timed out waiting for the migration lock after {LockTimeoutSeconds}s");
            default: throw new Error("MySQL did not grant the migration lock");
        }

        try
        {
            connection.Execute("SET SESSION autocommit = 1");
            EnsureHistory(connection);
            var history = connection.Query<HistoryRow>(
                "SELECT installed_rank AS InstalledRank, version AS Version, description AS Description, type AS Type, script AS Script, checksum AS Checksum, execution_time AS ExecutionTime, success AS Success FROM `flyway_schema_history` ORDER BY installed_rank").ToList();
            var pending = ValidateHistory(history, migrations);
            if (history.Count == 0) EnsureEmptySchema(connection, true);

            var rank = history.Select(h => h.InstalledRank).DefaultIfEmpty(0).Max() + 1;
            foreach (var migration in pending) ApplyMigration(connection, migration, rank++, logger);
            logger.LogInformation("User schema ready ({Total} migrations, {Applied} applied)", migrations.Count, pending.Count);
        }
        finally
        {
            try
            {
                if (connection.ExecuteScalar<long?>("SELECT RELEASE_LOCK(@lockName)", new { lockName }, commandTimeout: 15) != 1)
                    logger.LogWarning("Migration lock release was not confirmed; closing its database connection");
            }
            catch (Exception)
            {
                logger.LogWarning("Migration connection closed before its lock could be explicitly released");
            }
        }
    }

    static void EnsureHistory(MySqlConnection connection)
    {
        var tableType = connection.Query<string>(
            "SELECT TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @HistoryTable",
            new { HistoryTable }).ToList();
        if (tableType.Count == 0)
        {
            EnsureEmptySchema(connection, false);
            connection.Execute("""
                CREATE TABLE `flyway_schema_history` (
                  `installed_rank` INT NOT NULL,
                  `version` VARCHAR(50),
                  `description` VARCHAR(200) NOT NULL,
                  `type` VARCHAR(20) NOT NULL,
                  `script` VARCHAR(1000) NOT NULL,
                  `checksum` INT,
                  `installed_by` VARCHAR(100) NOT NULL,
                  `installed_on` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `execution_time` INT NOT NULL,
                  `success` BOOL NOT NULL,
                  CONSTRAINT `flyway_schema_history_pk` PRIMARY KEY (`installed_rank`),
                  INDEX `flyway_schema_history_s_idx` (`success`)
                ) ENGINE = InnoDB
                """);
        }
        else if (tableType is not ["BASE TABLE"])
        {
            throw new Error("The migration history object is not a regular table");
        }
    }

    static void EnsureEmptySchema(MySqlConnection connection, bool historyExists)
    {
        var count = connection.ExecuteScalar<long>("""
            SELECT
              (SELECT COUNT(*) FROM information_schema.tables
               WHERE TABLE_SCHEMA = DATABASE() AND (@historyExists = 0 OR TABLE_NAME <> @HistoryTable)) +
              (SELECT COUNT(*) FROM information_schema.routines WHERE ROUTINE_SCHEMA = DATABASE()) +
              (SELECT COUNT(*) FROM information_schema.events WHERE EVENT_SCHEMA = DATABASE())
            """, new { historyExists = historyExists ? 1 : 0, HistoryTable });
        if (count != 0)
            throw new Error("The database contains objects without applied migration history; automatic baselining is disabled");
    }

    static List<Migration> ValidateHistory(List<HistoryRow> history, List<Migration> migrations)
    {
        var previousRank = 0;
        foreach (var row in history)
        {
            if (!row.Success)
                throw new Error($"Migration history contains a failed entry at rank {row.InstalledRank}; reconcile the schema and repair history before restarting");
            if (row.InstalledRank <= previousRank || row.Type != "SQL" || row.Version is null
                || !Regex.IsMatch(row.Version, @"\A[1-9][0-9]*\z") || row.ExecutionTime < 0)
                throw new Error("Migration history contains an unsupported or invalid entry; baselines and repeatables are not supported");
            previousRank = row.InstalledRank;
        }

        if (history.Count > migrations.Count) throw new Error("Migration history contains versions absent from this release");

        foreach (var (row, migration) in history.Zip(migrations))
        {
            if (row.Version != migration.Version.ToString())
                throw new Error("Migration history is not an ordered prefix of the bundled versions; missing, duplicate, future or out-of-order migrations require reconciliation");
            if (row.Script != migration.Script || row.Description != migration.Description)
                throw new Error($"Migration history metadata differs for {migration.Script}");
            if (row.Checksum is { } checksum && checksum != migration.Checksum)
                throw new Error($"Migration checksum differs for {migration.Script}; restore the original file");
        }

        return migrations.Skip(history.Count).ToList();
    }

    static void ApplyMigration(MySqlConnection connection, Migration migration, int rank, ILogger logger)
    {
        connection.Execute("""
            INSERT INTO `flyway_schema_history`
              (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
            VALUES (@rank, @version, @Description, 'SQL', @Script, @Checksum, SUBSTRING_INDEX(USER(), '@', 1), 0, 0)
            """, new { rank, version = migration.Version.ToString(), migration.Description, migration.Script, migration.Checksum });

        var stopwatch = Stopwatch.StartNew();
        logger.LogInformation("Applying user schema migration {Script}", migration.Script);
        foreach (var statement in migration.Statements)
        {
            try
            {
                using var command = new MySqlCommand(statement, connection);
                command.ExecuteNonQuery();
            }
            catch (MySqlException error)
            {
                throw new Error($"Migration {migration.Script} failed (MySQL error {error.Number}); its history remains failed. Reconcile any partial DDL and repair history before restarting");
            }
        }

        var updated = connection.Execute(
            "UPDATE `flyway_schema_history` SET execution_time = @elapsed, success = 1 WHERE installed_rank = @rank AND success = 0",
            new { elapsed = (int)Math.Min(stopwatch.ElapsedMilliseconds, int.MaxValue), rank });
        if (updated != 1)
            throw new Error($"Migration history changed while applying {migration.Script}; startup has been stopped");
    }
}
