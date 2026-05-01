defmodule CoworkUser.AppConfig do
  @enforce_keys [:port, :eureka_server_url, :eureka_app_name, :eureka_instance_host, :eureka_instance_id]
  defstruct [
    :port,
    :eureka_server_url,
    :eureka_app_name,
    :eureka_instance_host,
    :eureka_instance_id,
    :eureka_instance_port,
    :config_server_url,
    :config_profile,
    :kafka_bootstrap_servers,
    :kafka_topic,
    :kafka_group_id,
    :kafka_enabled,
    :minio_region,
    :minio_internal_endpoint,
    :minio_public_endpoint,
    :minio_access_key,
    :minio_secret_key,
    :minio_bucket,
    :minio_path_style,
    :presigned_put_expiry_minutes,
    :presigned_get_expiry_minutes,
    :max_file_size_bytes,
    :allowed_content_types
  ]

  @persistent_key {__MODULE__, :config}

  def load do
    case :persistent_term.get(@persistent_key, :undefined) do
      :undefined ->
        remote = fetch_from_config_server()
        config = build(remote)
        :persistent_term.put(@persistent_key, config)
        config

      config ->
        config
    end
  end

  def refresh! do
    config = build(fetch_from_config_server())
    :persistent_term.put(@persistent_key, config)
    config
  end

  defp build(remote) do
    port = lookup(remote, ["PORT", "SERVER_PORT", "server_port"], "8082") |> String.to_integer()
    app_name = lookup(remote, ["EUREKA_APP_NAME", "eureka_app_name"], "cowork-user")
    instance_host = lookup(remote, ["EUREKA_INSTANCE_HOST", "eureka_instance_host"], "localhost")
    instance_port = lookup(remote, ["EUREKA_INSTANCE_PORT", "eureka_instance_port"], Integer.to_string(port)) |> String.to_integer()

    %__MODULE__{
      port: port,
      eureka_server_url:
        lookup(
          remote,
          [
            "EUREKA_SERVER_URL",
            "eureka_server_url"
          ],
          "http://localhost:8761/eureka"
        ),
      eureka_app_name: app_name,
      eureka_instance_host: instance_host,
      eureka_instance_id: lookup(remote, ["EUREKA_INSTANCE_ID"], "#{instance_host}:#{app_name}:#{instance_port}"),
      eureka_instance_port: instance_port,
      config_server_url: System.get_env("APP_CONFIG_URL"),
      config_profile: System.get_env("APP_PROFILE", "local"),
      kafka_bootstrap_servers: lookup(remote, ["KAFKA_BOOTSTRAP_SERVERS", "kafka_bootstrap_servers"], "localhost:9094"),
      kafka_topic: lookup(remote, ["KAFKA_TOPIC_USER_SYNC"], "user.data.sync"),
      kafka_group_id: lookup(remote, ["KAFKA_GROUP_ID", "kafka_group_id"], "cowork-user"),
      kafka_enabled: lookup(remote, ["KAFKA_ENABLED"], "true") == "true",
      minio_region: lookup(remote, ["MINIO_REGION", "minio_region"], "ap-northeast-2"),
      minio_internal_endpoint: lookup(remote, ["MINIO_INTERNAL_ENDPOINT", "minio_internal_endpoint"], "http://localhost:9000"),
      minio_public_endpoint: lookup(remote, ["MINIO_PUBLIC_ENDPOINT", "minio_public_endpoint"], "http://localhost:9000"),
      minio_access_key: lookup(remote, ["MINIO_ACCESS_KEY", "minio_access_key"], ""),
      minio_secret_key: lookup(remote, ["MINIO_SECRET_KEY", "minio_secret_key"], ""),
      minio_bucket: lookup(remote, ["MINIO_BUCKET", "minio_bucket"], "cowork-bucket"),
      minio_path_style: lookup(remote, ["MINIO_PATH_STYLE_ACCESS_ENABLED", "minio_path_style_access_enabled"], "true") == "true",
      presigned_put_expiry_minutes: lookup(remote, ["MINIO_PRESIGNED_PUT_EXPIRY_MINUTES", "minio_presigned_put_expiry_minutes"], "5") |> String.to_integer(),
      presigned_get_expiry_minutes: lookup(remote, ["MINIO_PRESIGNED_GET_EXPIRY_MINUTES", "minio_presigned_get_expiry_minutes"], "15") |> String.to_integer(),
      max_file_size_bytes: lookup(remote, ["MINIO_MAX_FILE_SIZE_BYTES", "minio_max_file_size_bytes"], "5242880") |> String.to_integer(),
      allowed_content_types: parse_csv_or_list(lookup(remote, ["MINIO_ALLOWED_CONTENT_TYPES"], "image/jpeg,image/png,image/webp"))
    }
  end

  defp fetch_from_config_server do
    with url when is_binary(url) and url != "" <- System.get_env("APP_CONFIG_URL"),
         profile <- System.get_env("APP_PROFILE", "local"),
         {:ok, response} <- Req.get(url: "#{String.trim_trailing(url, "/")}/cowork-user/#{profile}"),
         {:ok, merged} <- merge_property_sources(response.body) do
      merged
    else
      _ -> %{}
    end
  end

  defp merge_property_sources(%{"propertySources" => property_sources}) when is_list(property_sources) do
    merged =
      property_sources
      |> Enum.reverse()
      |> Enum.reduce(%{}, fn
        %{"source" => source}, acc when is_map(source) ->
          Enum.reduce(source, acc, fn {key, value}, inner -> Map.put(inner, key, stringify(value)) end)

        _, acc ->
          acc
      end)

    {:ok, merged}
  end

  defp merge_property_sources(_), do: {:error, :invalid_response}

  defp stringify(nil), do: ""
  defp stringify(value) when is_binary(value), do: value
  defp stringify(value), do: to_string(value)

  defp lookup(remote, keys, default) do
    Enum.find_value(keys, default, fn key ->
      System.get_env(key) || Map.get(remote, key)
    end)
  end

  defp parse_csv_or_list(value) when is_binary(value) do
    value
    |> String.trim()
    |> String.trim_leading("[")
    |> String.trim_trailing("]")
    |> String.split(",", trim: true)
    |> Enum.map(fn entry ->
      entry
      |> String.trim()
      |> String.trim("\"")
      |> String.trim("'")
    end)
    |> Enum.reject(&(&1 == ""))
  end
end
