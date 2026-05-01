defmodule CoworkUser.Storage.Minio do
  alias CoworkUser.AppConfig

  def validate_content_type(content_type) do
    if content_type in AppConfig.load().allowed_content_types do
      :ok
    else
      {:error, "허용되지 않는 파일 형식입니다."}
    end
  end

  def build_object_key(user_id, content_type) do
    ext = content_type |> String.split("/") |> List.last()
    "profiles/#{user_id}/#{Ecto.UUID.generate()}.#{ext}"
  end

  def presigned_put_url(object_key, content_type) do
    config = s3_config(:public)

    ExAws.S3.presigned_url(
      config,
      :put,
      AppConfig.load().minio_bucket,
      object_key,
      expires_in: AppConfig.load().presigned_put_expiry_minutes * 60,
      headers: [{"content-type", content_type}]
    )
    |> unwrap!()
  end

  def presigned_get_url(object_key) do
    config = s3_config(:public)

    ExAws.S3.presigned_url(
      config,
      :get,
      AppConfig.load().minio_bucket,
      object_key,
      expires_in: AppConfig.load().presigned_get_expiry_minutes * 60
    )
    |> unwrap!()
  end

  def verify_upload(user_id, object_key) do
    if String.starts_with?(object_key, "profiles/#{user_id}/") do
      case ExAws.S3.head_object(AppConfig.load().minio_bucket, object_key) |> ExAws.request(s3_config(:internal)) do
        {:ok, %{headers: headers}} ->
          content_length =
            headers
            |> Enum.find_value("0", fn {name, value} ->
              if String.downcase(to_string(name)) == "content-length", do: to_string(value), else: nil
            end)
            |> String.to_integer()

          if content_length > AppConfig.load().max_file_size_bytes do
            delete_object(object_key)
            {:error, {:payload_too_large, "파일 크기가 허용 한도를 초과합니다."}}
          else
            :ok
          end

        {:error, {:http_error, 404, _}} ->
          {:error, {:conflict, "S3에 파일이 없습니다. 업로드를 먼저 완료하세요."}}

        {:error, reason} ->
          {:error, {:validation, inspect(reason)}}
      end
    else
      {:error, {:validation, "유효하지 않은 object_key입니다."}}
    end
  end

  def delete_object(nil), do: :ok

  def delete_object(object_key) do
    ExAws.S3.delete_object(AppConfig.load().minio_bucket, object_key)
    |> ExAws.request(s3_config(:internal))

    :ok
  end

  defp s3_config(mode) do
    config = AppConfig.load()
    endpoint = if mode == :public, do: config.minio_public_endpoint, else: config.minio_internal_endpoint
    uri = URI.parse(endpoint)

    [
      region: config.minio_region,
      access_key_id: config.minio_access_key,
      secret_access_key: config.minio_secret_key,
      scheme: uri.scheme,
      host: uri.host,
      port: uri.port,
      s3: [path_style: config.minio_path_style]
    ]
  end

  defp unwrap!({:ok, value}), do: value
  defp unwrap!({:error, reason}), do: raise("presigned url generation failed: #{inspect(reason)}")
end
