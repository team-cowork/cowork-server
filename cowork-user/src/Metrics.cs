using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace CoworkUser;

public static partial class Metrics
{
    sealed class Series
    {
        public long Count;
        public long Ticks;
    }

    static readonly ConcurrentDictionary<(string Uri, int Status), Series> Requests = new();

    [GeneratedRegex(@"/\d+")]
    private static partial Regex NumericSegment();

    internal static string NormalizeUri(string path) => NumericSegment().Replace(path, "/:id");

    public static void Record(string path, int status, TimeSpan elapsed)
    {
        var series = Requests.GetOrAdd((NormalizeUri(path), status), _ => new Series());
        Interlocked.Increment(ref series.Count);
        Interlocked.Add(ref series.Ticks, elapsed.Ticks);
    }

    public static string RenderPrometheus()
    {
        var text = new StringBuilder("# TYPE http_server_requests_seconds_count counter\n# TYPE http_server_requests_seconds_sum counter\n");
        foreach (var ((uri, status), series) in Requests)
        {
            var labels = $"application=\"cowork-user\",uri=\"{uri.Replace("\\", "\\\\").Replace("\"", "\\\"")}\",status=\"{status}\"";
            text.Append(CultureInfo.InvariantCulture, $"http_server_requests_seconds_count{{{labels}}} {Interlocked.Read(ref series.Count)}\n");
            text.Append(CultureInfo.InvariantCulture, $"http_server_requests_seconds_sum{{{labels}}} {Math.Round(TimeSpan.FromTicks(Interlocked.Read(ref series.Ticks)).TotalSeconds, 6)}\n");
        }

        using var process = Process.GetCurrentProcess();
        ThreadPool.GetAvailableThreads(out var availableWorkers, out _);
        ThreadPool.GetMaxThreads(out var maxWorkers, out _);
        text.Append(CultureInfo.InvariantCulture, $"process_working_set_bytes{{application=\"cowork-user\"}} {process.WorkingSet64}\n");
        text.Append(CultureInfo.InvariantCulture, $"dotnet_gc_heap_bytes{{application=\"cowork-user\"}} {GC.GetTotalMemory(false)}\n");
        text.Append(CultureInfo.InvariantCulture, $"dotnet_threadpool_busy_threads{{application=\"cowork-user\"}} {maxWorkers - availableWorkers}\n");
        text.Append(CultureInfo.InvariantCulture, $"dotnet_threadpool_queue_length{{application=\"cowork-user\"}} {ThreadPool.PendingWorkItemCount}\n");
        return text.ToString();
    }
}
