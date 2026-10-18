using CoworkUser;
using CoworkUser.Kafka;
using Dapper;
using MySqlConnector;
using StackExchange.Redis;

DefaultTypeMap.MatchNamesWithUnderscores = true;

var logPath = Environment.GetEnvironmentVariable("LOG_PATH") is { Length: > 0 } configuredLogPath ? configuredLogPath
    : Environment.GetEnvironmentVariable("DOTNET_RUNNING_IN_CONTAINER") == "true" ? "/var/log/cowork/user/application.log"
    : null;
using var logProvider = new LineLoggerProvider(logPath);
void ConfigureLogging(ILoggingBuilder logging) => logging.ClearProviders().AddProvider(logProvider)
    .SetMinimumLevel(LogLevel.Information)
    .AddFilter("Microsoft", LogLevel.Warning)
    .AddFilter("System.Net.Http", LogLevel.Warning);

App.Logs = LoggerFactory.Create(ConfigureLogging);
var startup = App.Logs.CreateLogger("CoworkUser.Startup");

var config = AppConfig.Load();
App.Config = config;
SchemaMigration.Run(config.ConnectionString, startup);
App.Db = new MySqlDataSource(config.ConnectionString);
Accounts.Redis = await ConnectionMultiplexer.ConnectAsync(new ConfigurationOptions
{
    EndPoints = { { config.RedisHost, config.RedisPort } },
    // The display-name cache is optional; reads fall back to MySQL while Redis reconnects.
    AbortOnConnectFail = false,
});

var builder = WebApplication.CreateBuilder(args);
ConfigureLogging(builder.Logging);
builder.WebHost.ConfigureKestrel(kestrel =>
{
    kestrel.ListenAnyIP(config.Port);
    // The previous runtime rejected larger bodies with 413.
    kestrel.Limits.MaxRequestBodySize = 8_000_000;
});
builder.Services.Configure<HostOptions>(options => options.ShutdownTimeout = TimeSpan.FromSeconds(25));
builder.Services.AddSingleton(config);
builder.Services.AddSingleton<Producer>();
builder.Services.AddSingleton<KafkaInspector>();
builder.Services.AddSingleton<ProjectionReadiness>();
builder.Services.AddSingleton<Consumers>();
builder.Services.AddSingleton<ProfileSnapshotPublisher>();
builder.Services.AddHostedService<OutboxRelay>();
builder.Services.AddHostedService(services => services.GetRequiredService<ProfileSnapshotPublisher>());
builder.Services.AddHostedService(services => services.GetRequiredService<ProjectionReadiness>());
builder.Services.AddHostedService(services => services.GetRequiredService<Consumers>());
builder.Services.AddHostedService<Eureka>();

var app = builder.Build();
var readiness = app.Services.GetRequiredService<ProjectionReadiness>();
readiness.BecameReady = app.Services.GetRequiredService<ProfileSnapshotPublisher>().PublishNow;
readiness.ReplayRequested = app.Services.GetRequiredService<Consumers>().ForceReplay;

var http = App.Logs.CreateLogger("CoworkUser.Http");
app.Run(context => Router.HandleAsync(context, readiness, http));
await app.RunAsync();
