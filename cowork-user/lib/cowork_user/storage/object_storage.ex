defmodule CoworkUser.Storage.ObjectStorage do
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
    "profile-uploads/#{user_id}/#{Ecto.UUID.generate()}.#{ext}"
  end

  def presigned_put_url(object_key, content_type) do
    config = s3_config(:signing)

    ExAws.S3.presigned_url(
      config,
      :put,
      AppConfig.load().s3_bucket,
      object_key,
      expires_in: AppConfig.load().presigned_put_expiry_minutes * 60,
      headers: [{"content-type", content_type}]
    )
    |> unwrap()
  end

  def presigned_get_url(object_key) do
    config = s3_config(:signing)

    ExAws.S3.presigned_url(
      config,
      :get,
      AppConfig.load().s3_bucket,
      object_key,
      expires_in: AppConfig.load().presigned_get_expiry_minutes * 60
    )
    |> unwrap()
  end

  def verify_upload(user_id, object_key) do
    if owned_key?(object_key, "profile-uploads", user_id) do
      config = AppConfig.load()

      with {:ok, %{headers: headers}} <-
             ExAws.S3.head_object(config.s3_bucket, object_key)
             |> ExAws.request(s3_config()),
           {:ok, metadata} <-
             validate_metadata(headers, config.max_file_size_bytes, config.allowed_content_types),
           final_key =
             "profiles/#{user_id}/#{Ecto.UUID.generate()}.#{extension(metadata.content_type)}",
           {:ok, _} <-
             ExAws.S3.put_object_copy(config.s3_bucket, final_key, config.s3_bucket, object_key,
               copy_source_if_match: metadata.etag,
               metadata_directive: :REPLACE,
               content_type: metadata.content_type,
               cache_control: "private, no-store"
             )
             |> ExAws.request(s3_config()) do
        {:ok, final_key}
      else
        {:error, {:http_error, status, _}} when status in [404, 412] ->
          {:error, {:conflict, "업로드한 파일이 없거나 변경되었습니다. 다시 업로드하세요."}}

        {:error, reason} ->
          {:error, reason}
      end
    else
      {:error, {:validation, "유효하지 않은 object_key입니다."}}
    end
  end

  def validate_metadata(headers, max_size, allowed_types) do
    headers =
      Map.new(headers, fn {key, value} -> {String.downcase(to_string(key)), to_string(value)} end)

    size = parse_content_length(Map.get(headers, "content-length", ""))
    content_type = Map.get(headers, "content-type")
    etag = Map.get(headers, "etag")

    cond do
      is_nil(size) or size <= 0 ->
        {:error, {:validation, "파일 크기 메타데이터가 유효하지 않습니다."}}

      size > max_size ->
        {:error, {:payload_too_large, "파일 크기가 허용 한도를 초과합니다."}}

      content_type not in allowed_types or
          content_type not in ["image/jpeg", "image/png", "image/webp"] ->
        {:error, {:validation, "허용되지 않는 파일 형식입니다."}}

      is_nil(etag) or etag == "" ->
        {:error, {:validation, "파일 무결성 메타데이터가 유효하지 않습니다."}}

      true ->
        {:ok, %{size: size, content_type: content_type, etag: etag}}
    end
  end

  defp owned_key?(key, prefix, user_id) do
    is_binary(key) and String.starts_with?(key, "#{prefix}/#{user_id}/") and
      Regex.match?(
        ~r/^(profiles|profile-uploads)\/[1-9][0-9]*\/[0-9a-f-]{36}\.(jpeg|jpg|png|webp)$/,
        key
      )
  end

  defp extension("image/jpeg"), do: "jpg"
  defp extension("image/png"), do: "png"
  defp extension("image/webp"), do: "webp"

  def delete_object(nil), do: :ok

  def delete_object(object_key) do
    unless is_binary(object_key) and
             Regex.match?(
               ~r/^(profiles|profile-uploads)\/[1-9][0-9]*\/[0-9a-f-]{36}\.(jpeg|jpg|png|webp)$/,
               object_key
             ) do
      raise ArgumentError, "invalid profile object key"
    end

    ExAws.S3.delete_object(AppConfig.load().s3_bucket, object_key)
    |> ExAws.request(s3_config())

    :ok
  end

  defp s3_config(mode \\ :internal) do
    config = AppConfig.load()

    endpoint =
      if mode == :signing, do: config.public_api_base_url, else: config.s3_internal_endpoint

    uri = URI.parse(endpoint)

    %{
      region: config.s3_region,
      access_key_id: config.s3_access_key,
      secret_access_key: config.s3_secret_key,
      scheme: "#{uri.scheme}://",
      host: uri.host,
      port: uri.port,
      s3: %{path_style: config.s3_path_style}
    }
  end

  defp unwrap({:ok, value}) do
    signed = URI.parse(value)
    api = String.trim_trailing(AppConfig.load().public_api_base_url, "/")
    # Gateway restores the signed API Host and removes only /api/storage.
    {:ok, "#{api}/api/storage#{signed.path}?#{signed.query}"}
  end

  defp unwrap({:error, reason}), do: {:error, {:presigned_url_generation_failed, inspect(reason)}}

  defp parse_content_length(value) do
    case Integer.parse(value) do
      {parsed, ""} when parsed > 0 -> parsed
      _ -> nil
    end
  end
end
