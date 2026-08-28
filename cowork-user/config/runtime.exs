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
    System.get_env("DB_URL")

db_pool_size = String.to_integer(System.get_env("DB_POOL_SIZE", "10"))

repo_config =
  if db_url do
    [url: normalize_mysql_url.(db_url)]
  else
    [
      hostname: System.fetch_env!("DB_HOST"),
      port: System.fetch_env!("DB_PORT") |> String.to_integer(),
      database: System.fetch_env!("DB_NAME"),
      username: System.fetch_env!("DB_USERNAME"),
      password: System.fetch_env!("DB_PASSWORD")
    ]
  end

config :cowork_user, CoworkUser.Repo, Keyword.put(repo_config, :pool_size, db_pool_size)
