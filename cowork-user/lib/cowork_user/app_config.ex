defmodule CoworkUser.AppConfig do
  @enforce_keys [
    :port,
    :eureka_server_url,
    :eureka_app_name,
    :eureka_instance_host,
    :eureka_instance_id
  ]
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
    :s3_region,
    :s3_internal_endpoint,
    :s3_public_endpoint,
    :s3_access_key,
    :s3_secret_key,
    :s3_bucket,
    :s3_path_style,
    :presigned_put_expiry_minutes,
    :presigned_get_expiry_minutes,
    :max_file_size_bytes,
    :allowed_content_types,
    :redis_host,
    :redis_port,
    :team_service_url
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

    instance_port =
      lookup(remote, ["EUREKA_INSTANCE_PORT", "eureka_instance_port"], Integer.to_string(port))
      |> String.to_integer()

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
      eureka_instance_id:
        lookup(remote, ["EUREKA_INSTANCE_ID"], "#{instance_host}:#{app_name}:#{instance_port}"),
      eureka_instance_port: instance_port,
      config_server_url: System.get_env("APP_CONFIG_URL"),
      config_profile: System.get_env("APP_PROFILE", "local"),
      kafka_bootstrap_servers:
        lookup(remote, ["KAFKA_BOOTSTRAP_SERVERS", "kafka_bootstrap_servers"], "localhost:9094"),
      kafka_topic: lookup(remote, ["KAFKA_TOPIC_USER_SYNC"], "user.data.sync"),
      kafka_group_id: lookup(remote, ["KAFKA_GROUP_ID", "kafka_group_id"], "cowork-user"),
      kafka_enabled: lookup(remote, ["KAFKA_ENABLED"], "true") == "true",
      s3_region: lookup(remote, ["S3_REGION", "s3_region"], "ap-northeast-2"),
      s3_internal_endpoint:
        lookup(
          remote,
          ["S3_INTERNAL_ENDPOINT", "s3_internal_endpoint"],
          "http://localhost:9000"
        ),
      s3_public_endpoint:
        lookup(
          remote,
          ["S3_PUBLIC_ENDPOINT", "s3_public_endpoint"],
          "http://localhost:9000"
        ),
      s3_access_key: lookup(remote, ["S3_ACCESS_KEY", "s3_access_key"], ""),
      s3_secret_key: lookup(remote, ["S3_SECRET_KEY", "s3_secret_key"], ""),
      s3_bucket: lookup(remote, ["S3_BUCKET", "s3_bucket"], "cowork-bucket"),
      s3_path_style:
        lookup(
          remote,
          ["S3_PATH_STYLE_ACCESS_ENABLED", "s3_path_style_access_enabled"],
          "true"
        ) == "true",
      presigned_put_expiry_minutes:
        lookup(
          remote,
          ["S3_PRESIGNED_PUT_EXPIRY_MINUTES", "s3_presigned_put_expiry_minutes"],
          "5"
        )
        |> String.to_integer(),
      presigned_get_expiry_minutes:
        lookup(
          remote,
          ["S3_PRESIGNED_GET_EXPIRY_MINUTES", "s3_presigned_get_expiry_minutes"],
          "15"
        )
        |> String.to_integer(),
      max_file_size_bytes:
        lookup(remote, ["S3_MAX_FILE_SIZE_BYTES", "s3_max_file_size_bytes"], "5242880")
        |> String.to_integer(),
      allowed_content_types:
        parse_csv_or_list(
          lookup(remote, ["S3_ALLOWED_CONTENT_TYPES"], "image/jpeg,image/png,image/webp")
        ),
      redis_host: lookup(remote, ["REDIS_HOST", "redis_host"], "localhost"),
      redis_port: lookup(remote, ["REDIS_PORT", "redis_port"], "6379") |> String.to_integer(),
      team_service_url:
        lookup(remote, ["TEAM_SERVICE_URL", "team_service_url"], "http://localhost:8085")
    }
  end

  defp fetch_from_config_server do
    case System.get_env("APP_CONFIG_URL") do
      url when is_binary(url) and url != "" ->
        profile = System.get_env("APP_PROFILE", "local")

        case Req.get(url: "#{String.trim_trailing(url, "/")}/cowork-user/#{profile}") do
          {:ok, %{status: 200, body: body}} ->
            case merge_property_sources(body) do
              {:ok, merged} -> merged
              {:error, reason} -> raise "invalid Config Server response: #{inspect(reason)}"
            end

          {:ok, %{status: status}} ->
            raise "Config Server returned HTTP #{status} for cowork-user/#{profile}"

          {:error, reason} ->
            raise "Config Server unavailable for cowork-user/#{profile}: #{inspect(reason)}"
        end

      _ ->
        %{}
    end
  end

  defp merge_property_sources(%{"propertySources" => property_sources})
       when is_list(property_sources) do
    merged =
      property_sources
      |> Enum.reverse()
      |> Enum.reduce(%{}, fn
        %{"source" => source}, acc when is_map(source) ->
          Enum.reduce(source, acc, fn {key, value}, inner ->
            Map.put(inner, key, stringify(value))
          end)

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
