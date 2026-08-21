defmodule CoworkUser.AppConfigTest do
  use ExUnit.Case, async: false

  alias CoworkUser.AppConfig

  @persistent_key {AppConfig, :config}
  @environment_keys [
    "APP_CONFIG_URL",
    "APP_PROFILE",
    "PORT",
    "SERVER_PORT",
    "EUREKA_SERVER_URL",
    "EUREKA_APP_NAME",
    "EUREKA_INSTANCE_HOST",
    "EUREKA_INSTANCE_ID",
    "EUREKA_INSTANCE_PORT",
    "KAFKA_BOOTSTRAP_SERVERS",
    "KAFKA_TOPIC_USER_SYNC",
    "KAFKA_GROUP_ID",
    "KAFKA_ENABLED",
    "S3_REGION",
    "S3_INTERNAL_ENDPOINT",
    "S3_PUBLIC_ENDPOINT",
    "S3_ACCESS_KEY",
    "S3_SECRET_KEY",
    "S3_BUCKET",
    "S3_PATH_STYLE_ACCESS_ENABLED",
    "S3_PRESIGNED_PUT_EXPIRY_MINUTES",
    "S3_PRESIGNED_GET_EXPIRY_MINUTES",
    "S3_MAX_FILE_SIZE_BYTES",
    "S3_ALLOWED_CONTENT_TYPES",
    "REDIS_HOST",
    "REDIS_PORT",
    "TEAM_SERVICE_URL"
  ]

  setup do
    original_environment =
      Map.new(@environment_keys, fn key -> {key, System.get_env(key)} end)

    Enum.each(@environment_keys, &System.delete_env/1)
    :persistent_term.erase(@persistent_key)

    on_exit(fn ->
      :persistent_term.erase(@persistent_key)

      Enum.each(original_environment, fn
        {key, nil} -> System.delete_env(key)
        {key, value} -> System.put_env(key, value)
      end)
    end)

    :ok
  end

  test "loads local defaults when Config Server is not configured" do
    config = AppConfig.load()

    assert config.port == 8082
    assert config.config_profile == "local"
    assert config.eureka_server_url == "http://localhost:8761/eureka"
    assert config.kafka_bootstrap_servers == "localhost:9094"
    assert config.s3_internal_endpoint == "http://localhost:9000"
    assert config.redis_host == "localhost"
    assert config.redis_port == 6379
    assert config.team_service_url == "http://localhost:8085"
  end

  test "environment variables override defaults" do
    System.put_env(%{
      "PORT" => "9082",
      "APP_PROFILE" => "prod",
      "EUREKA_INSTANCE_HOST" => "user.internal",
      "KAFKA_ENABLED" => "false",
      "S3_ALLOWED_CONTENT_TYPES" => "image/png, application/pdf",
      "REDIS_PORT" => "6380"
    })

    config = AppConfig.load()

    assert config.port == 9082
    assert config.config_profile == "prod"
    assert config.eureka_instance_host == "user.internal"
    assert config.eureka_instance_id == "user.internal:cowork-user:9082"
    refute config.kafka_enabled
    assert config.allowed_content_types == ["image/png", "application/pdf"]
    assert config.redis_port == 6380
  end
end
