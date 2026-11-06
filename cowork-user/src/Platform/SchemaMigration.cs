using System.Diagnostics;

namespace CoworkUser.Platform;

public static class SchemaMigration
{
    public static void Run(ILogger logger)
    {
        using var process = new Process
        {
            StartInfo = new ProcessStartInfo("flyway")
            {
                WorkingDirectory = Path.Combine(AppContext.BaseDirectory, "db"),
                ArgumentList = { "-configFiles=flyway.toml", "migrate" },
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            },
        };
        process.OutputDataReceived += (_, output) =>
        {
            if (!string.IsNullOrWhiteSpace(output.Data))
                logger.LogInformation("{FlywayMessage}", output.Data);
        };
        process.ErrorDataReceived += (_, output) =>
        {
            if (!string.IsNullOrWhiteSpace(output.Data))
                logger.LogError("{FlywayMessage}", output.Data);
        };

        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        process.WaitForExit();
        if (process.ExitCode != 0)
            throw new InvalidOperationException($"Flyway migration failed with exit code {process.ExitCode}");
    }
}
