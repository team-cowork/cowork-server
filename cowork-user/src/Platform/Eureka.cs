using System.Net;
using CoworkUser.Config;
using CoworkUser.Kafka.Projections;

namespace CoworkUser.Platform;

/// <summary>Registers with Eureka only while projections are ready, and deregisters when they are not.</summary>
public sealed class Eureka(AppConfig config, ProjectionReadiness readiness, ILogger<Eureka> logger) : BackgroundService
{
    const int HeartbeatMs = 30_000;
    const int ReadinessRetryMs = 2_000;

    readonly HttpClient client = ControlPlane.Client();

    string ServerUrl => config.EurekaServerUrl.TrimEnd('/');
    string InstanceUrl => $"{ServerUrl}/apps/{config.EurekaAppName}/{config.EurekaInstanceId}";

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var backoff = 5_000;
        var registered = false;
        while (!stoppingToken.IsCancellationRequested)
        {
            int delay;
            if (!readiness.Ready)
            {
                if (registered)
                {
                    logger.LogError("projection readiness lost; deregistering from Eureka");
                    await SendAsync(HttpMethod.Delete, InstanceUrl, null);
                    registered = false;
                }
                delay = ReadinessRetryMs;
            }
            else
            {
                var status = registered ? await SendAsync(HttpMethod.Put, InstanceUrl, null) : await RegisterAsync();
                if (status is >= 200 and < 300)
                {
                    registered = true;
                    backoff = 5_000;
                    delay = HeartbeatMs;
                }
                else if (registered && status == 404)
                {
                    registered = false;
                    delay = 0;
                }
                else
                {
                    logger.LogWarning("eureka {Operation} failed: {Status}", registered ? "heartbeat" : "registration",
                        status is null ? ":transport_failure" : $"{{:status, {status}}}");
                    registered = false;
                    delay = backoff;
                    backoff = Math.Min(backoff * 2, 60_000);
                }
            }

            try
            {
                await Task.Delay(delay, stoppingToken);
            }
            catch (OperationCanceledException)
            {
            }
        }

        await SendAsync(HttpMethod.Delete, InstanceUrl, null);
    }

    Task<int?> RegisterAsync()
    {
        var host = config.EurekaInstanceHost;
        var port = config.EurekaInstancePort;
        return SendAsync(HttpMethod.Post, $"{ServerUrl}/apps/{config.EurekaAppName}", new
        {
            instance = new Dictionary<string, object>
            {
                ["instanceId"] = config.EurekaInstanceId,
                ["hostName"] = host,
                ["app"] = config.EurekaAppName.ToUpperInvariant(),
                ["ipAddr"] = host,
                ["vipAddress"] = config.EurekaAppName,
                ["secureVipAddress"] = config.EurekaAppName,
                ["status"] = "UP",
                ["port"] = new Dictionary<string, object> { ["$"] = port, ["@enabled"] = "true" },
                ["securePort"] = new Dictionary<string, object> { ["$"] = 443, ["@enabled"] = "false" },
                ["healthCheckUrl"] = $"http://{host}:{port}/actuator/health/readiness",
                ["statusPageUrl"] = $"http://{host}:{port}/actuator/health",
                ["homePageUrl"] = $"http://{host}:{port}/",
                ["dataCenterInfo"] = new Dictionary<string, object>
                {
                    ["@class"] = "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                    ["name"] = "MyOwn",
                },
                ["metadata"] = new Dictionary<string, object>
                {
                    ["management.port"] = port.ToString(),
                    ["prometheus.scrape"] = "true",
                    ["prometheus.path"] = "/actuator/prometheus",
                },
            },
        });
    }

    async Task<int?> SendAsync(HttpMethod method, string url, object? body)
    {
        try
        {
            using var request = ControlPlane.Request(method, url);
            if (body is not null) request.Content = JsonContent.Create(body);
            using var response = await client.SendAsync(request);
            return (int)response.StatusCode;
        }
        catch (Exception exception) when (exception is HttpRequestException or TaskCanceledException or WebException)
        {
            return null;
        }
    }
}
