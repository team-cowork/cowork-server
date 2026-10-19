using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Channels;

namespace CoworkUser.Platform;

/// <summary>
/// Writes one JSON object per event to stdout and to the shared log volume. The keys follow the other
/// services (@timestamp, level, service), which is what the log collection parses into Loki labels.
/// Events are queued and written by one background thread, so requests never wait on the disk. The queue is
/// bounded: when the writer falls behind, the newest events are dropped and counted, and the writer reports
/// the count as a WARN entry, so memory stays bounded and a loss is visible.
/// </summary>
public sealed class LineLoggerProvider : ILoggerProvider
{
    sealed record Entry(
        [property: JsonPropertyName("@timestamp")] string Timestamp,
        [property: JsonPropertyName("level")] string Level,
        [property: JsonPropertyName("service")] string Service,
        [property: JsonPropertyName("message")] string Message,
        [property: JsonPropertyName("request_id"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? RequestId
        );

    const long MaxBytes = 20_971_520;
    const int Keep = 4;
    const int QueueCapacity = 8_192;

    readonly Channel<string> queue;
    readonly Task writer;
    readonly string? path;
    StreamWriter? file;
    long dropped;

    public LineLoggerProvider(string? path)
    {
        this.path = path;
        queue = Channel.CreateBounded<string>(
            new BoundedChannelOptions(QueueCapacity) { SingleReader = true, FullMode = BoundedChannelFullMode.DropWrite },
            _ => Interlocked.Increment(ref dropped));
        if (path is not null)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            file = Open();
        }
        writer = Task.Factory.StartNew(WriteLoop, TaskCreationOptions.LongRunning);
    }

    StreamWriter Open() => new(new FileStream(path!, FileMode.Append, FileAccess.Write, FileShare.ReadWrite));

    public ILogger CreateLogger(string categoryName) => new LineLogger(this);

    static string Format(string level, string message, string? requestId) =>
        JsonSerializer.Serialize(new Entry(DateTime.UtcNow.ToString("O"), level, "cowork-user", message, requestId), Json.Event);

    void Write(LogLevel level, string message, string? requestId)
    {
        var name = level switch
        {
            LogLevel.Trace or LogLevel.Debug => "DEBUG",
            LogLevel.Information => "INFO",
            LogLevel.Warning => "WARN",
            _ => "ERROR",
        };
        queue.Writer.TryWrite(Format(name, message, requestId));
    }

    void WriteLoop()
    {
        var reader = queue.Reader;
        while (reader.WaitToReadAsync().AsTask().GetAwaiter().GetResult())
        {
            try
            {
                while (reader.TryRead(out var line))
                {
                    Console.Out.WriteLine(line);
                    file?.WriteLine(line);
                }
                ReportDropped();
                file?.Flush();
                if (file is not null && file.BaseStream.Length >= MaxBytes) Rotate();
            }
            catch (Exception)
            {
                // A full or unavailable volume, or a failed rotation, must not stop stdout logging.
            }
        }
        // Entries dropped after the last batch would otherwise never be reported.
        try
        {
            ReportDropped();
            file?.Flush();
        }
        catch (Exception)
        {
        }
    }

    void ReportDropped()
    {
        var lost = Interlocked.Exchange(ref dropped, 0);
        if (lost == 0) return;
        var notice = Format("WARN", $"Dropped {lost} log entries because the log writer fell behind", null);
        Console.Out.WriteLine(notice);
        file?.WriteLine(notice);
    }

    void Rotate()
    {
        file!.Dispose();
        file = null;
        try
        {
            for (var index = Keep - 1; index >= 0; index--)
            {
                var source = index == 0 ? path! : $"{path}.{index}";
                if (File.Exists(source)) File.Move(source, $"{path}.{index + 1}", true);
            }
        }
        finally
        {
            // A failed move keeps appending to the current file and retries on the next batch.
            file = Open();
        }
    }

    public void Dispose()
    {
        queue.Writer.TryComplete();
        writer.Wait(TimeSpan.FromSeconds(5));
        file?.Dispose();
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
