import Config

normalize_mysql_url = fn url ->
  cond do
    String.starts_with?(url, "ecto://") ->
      url

    String.starts_with?(url, "mysql://") ->
      username = System.get_env("DB_USERNAME", "cowork")
      password = URI.encode(System.get_env("DB_PASSWORD", ""))
      String.replace_prefix(url, "mysql://", "ecto://#{username}:#{password}@")

    true ->
      url
  end
end

db_url =
  System.get_env("DATABASE_URL") ||
    System.get_env("DB_URL") ||
    "ecto://cowork:@localhost:3306/cowork_user"

db_pool_size = String.to_integer(System.get_env("DB_POOL_SIZE", "10"))
server_port = String.to_integer(System.get_env("PORT") || System.get_env("SERVER_PORT") || "8082")
secret_key_base = System.get_env("SECRET_KEY_BASE") || String.duplicate("cowork-user-secret-", 4)

config :cowork_user, CoworkUser.Repo,
  url: normalize_mysql_url.(db_url),
  pool_size: db_pool_size

config :cowork_user, CoworkUser.Endpoint,
  http: [ip: {0, 0, 0, 0}, port: server_port],
  server: true,
  secret_key_base: secret_key_base
