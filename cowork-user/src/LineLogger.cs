using System.Text.Json;
using System.Text.Json.Serialization;

namespace CoworkUser;

/// <summary>
/// Writes one JSON object per event to stdout and to the shared log volume. The keys follow the other
/// services (@timestamp, level, service), which is what the log collection parses into Loki labels.
/// </summary>
public sealed class LineLoggerProvider : ILoggerProvider
{
    sealed record Entry(
        [property: JsonPropertyName("@timestamp")] string Timestamp,
        [property: JsonPropertyName("level")] string Level,
        [property: JsonPropertyName("service")] string Service,
        [property: JsonPropertyName("message")] string Message,
        [property: JsonPropertyName("request_id"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? RequestId);

    const long MaxBytes = 20_971_520;
    const int Keep = 4;

    readonly Lock gate = new();
    readonly string? path;
    StreamWriter? file;

    public LineLoggerProvider(string? path)
    {
        this.path = path;
        if (path is null) return;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        file = Open();
    }

    StreamWriter Open() => new(new FileStream(path!, FileMode.Append, FileAccess.Write, FileShare.ReadWrite)) { AutoFlush = true };

    public ILogger CreateLogger(string categoryName) => new LineLogger(this);

    void Write(LogLevel level, string message, string? requestId)
    {
        var name = level switch
        {
            LogLevel.Trace or LogLevel.Debug => "DEBUG",
            LogLevel.Information => "INFO",
            LogLevel.Warning => "WARN",
            _ => "ERROR",
        };
        var line = JsonSerializer.Serialize(new Entry(DateTime.UtcNow.ToString("O"), name, "cowork-user", message, requestId), Json.Event);
        lock (gate)
        {
            Console.Out.WriteLine(line);
            if (file is null) return;
            file.WriteLine(line);
            if (file.BaseStream.Length >= MaxBytes) Rotate();
        }
    }

    void Rotate()
    {
        file!.Dispose();
        for (var index = Keep - 1; index >= 0; index--)
        {
            var source = index == 0 ? path! : $"{path}.{index}";
            if (File.Exists(source)) File.Move(source, $"{path}.{index + 1}", true);
        }
        file = Open();
    }

    public void Dispose()
    {
        lock (gate) file?.Dispose();
    }

    sealed class LineLogger(LineLoggerProvider provider) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel != LogLevel.None;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            var message = formatter(state, exception);
            var requestId = (state as IReadOnlyList<KeyValuePair<string, object?>>)?.FirstOrDefault(pair => pair.Key == "RequestId").Value?.ToString();
            provider.Write(logLevel, exception is null ? message : $"{message}\n{exception}", requestId);
        }
    }
}
