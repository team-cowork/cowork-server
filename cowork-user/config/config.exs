import Config

config :cowork_user, ecto_repos: [CoworkUser.Repo]

config :cowork_user, CoworkUser.Repo,
  pool_size: String.to_integer(System.get_env("DB_POOL_SIZE", "10")),
  timeout: 15_000

config :logger, :default_formatter,
  format: "$time $metadata[$level] $message\n",
  metadata: [:request_id]

config :logger,
  backends: [:console, {LoggerFileBackend, :cowork_user_file}]

config :logger, :cowork_user_file,
  path: System.get_env("LOG_PATH", "/var/log/cowork/user/application.log"),
  format: "$date $time $metadata[$level] $message\n",
  metadata: [:request_id],
  level: :info
