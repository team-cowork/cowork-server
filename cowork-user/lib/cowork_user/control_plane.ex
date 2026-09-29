defmodule CoworkUser.ControlPlane do
  @moduledoc false

  def options(url) do
    endpoint = URI.parse(url)

    if endpoint.userinfo || !endpoint.host || endpoint.scheme not in ["http", "https"] do
      raise "Use a Config/Eureka HTTP(S) URL without credentials"
    end

    if System.get_env("APP_PROFILE", "local") == "prod" && endpoint.scheme != "https" do
      raise "Use HTTPS for production Config/Eureka"
    end

    username = System.fetch_env!("CONFIG_CLIENT_USERNAME")
    password = System.fetch_env!("CONFIG_CLIENT_PASSWORD")
    if username == "" || password == "", do: raise("Provide Config/Eureka bootstrap credentials")

    [url: url, auth: {:basic, "#{username}:#{password}"}, redirect: false, retry: false]
  end
end
