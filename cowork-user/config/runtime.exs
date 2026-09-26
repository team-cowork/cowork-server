import Config

# Database settings are loaded from Config Server by AppConfig before the Repo starts.
default_log_path =
  if System.get_env("RELEASE_NAME") do
    "/var/log/cowork/user/application.log"
  else
    Path.expand("../_build/log/application.log", __DIR__)
  end

log_path = System.get_env("LOG_PATH", default_log_path)
File.mkdir_p!(Path.dirname(log_path))
config :logger, :cowork_user_file, path: log_path
