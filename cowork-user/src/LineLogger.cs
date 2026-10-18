namespace CoworkUser;

/// <summary>
/// Writes "time [level] message" lines to stdout and to the shared log volume. Log collection
/// reads the bracketed level from these lines.
/// </summary>
public sealed class LineLoggerProvider : ILoggerProvider
{
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

    void Write(LogLevel level, string message)
    {
        var name = level switch
        {
            LogLevel.Trace or LogLevel.Debug => "debug",
            LogLevel.Information => "info",
            LogLevel.Warning => "warning",
            _ => "error",
        };
        var now = DateTime.UtcNow;
        lock (gate)
        {
            Console.Out.WriteLine($"{now:HH:mm:ss.fff} [{name}] {message}");
            if (file is null) return;
            file.WriteLine($"{now:yyyy-MM-dd HH:mm:ss.fff} [{name}] {message}");
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
            provider.Write(logLevel, exception is null ? message : $"{message}\n{exception}");
        }
    }
}
