defmodule CoworkUser.ControlPlane do
  @moduledoc false

  def options(url) do
    endpoint = URI.parse(url)

    if endpoint.userinfo || !endpoint.host || endpoint.scheme not in ["http", "https"] do
      raise "Use a Config/Eureka HTTP(S) URL without credentials"
    end

    production = System.get_env("APP_PROFILE", "local") == "prod"

    if production && endpoint.scheme != "https" && !private_ipv4?(endpoint.host) do
      raise "Use HTTPS or a private IPv4 HTTP URL for production Config/Eureka"
    end

    username = System.fetch_env!("CONFIG_CLIENT_USERNAME")
    password = System.fetch_env!("CONFIG_CLIENT_PASSWORD")
    if username == "" || password == "", do: raise("Provide Config/Eureka bootstrap credentials")

    [url: url, auth: {:basic, "#{username}:#{password}"}, redirect: false, retry: false]
  end

  # Only RFC1918 IPv4 literals: a host name would let DNS redirect plaintext credentials.
  defp private_ipv4?(host) do
    case :inet.parse_ipv4strict_address(String.to_charlist(host)) do
      {:ok, {10, _, _, _}} -> true
      {:ok, {172, second, _, _}} when second in 16..31 -> true
      {:ok, {192, 168, _, _}} -> true
      _ -> false
    end
  end
end
