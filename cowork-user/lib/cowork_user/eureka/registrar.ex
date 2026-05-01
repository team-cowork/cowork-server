defmodule CoworkUser.Eureka.Registrar do
  use GenServer

  require Logger

  @heartbeat_interval 30_000

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @impl true
  def init(opts) do
    config = Keyword.fetch!(opts, :config)
    state = %{config: config, backoff_ms: 5_000}
    send(self(), :register)
    {:ok, state}
  end

  @impl true
  def handle_info(:register, state) do
    case register(state.config) do
      :ok ->
        schedule_heartbeat()
        {:noreply, %{state | backoff_ms: 5_000}}

      {:error, reason} ->
        Logger.warning("eureka registration failed: #{inspect(reason)}")
        Process.send_after(self(), :register, state.backoff_ms)
        {:noreply, %{state | backoff_ms: min(state.backoff_ms * 2, 60_000)}}
    end
  end

  def handle_info(:heartbeat, state) do
    case heartbeat(state.config) do
      :ok ->
        schedule_heartbeat()
        {:noreply, state}

      {:error, :not_found} ->
        send(self(), :register)
        {:noreply, state}

      {:error, reason} ->
        Logger.warning("eureka heartbeat failed: #{inspect(reason)}")
        Process.send_after(self(), :register, state.backoff_ms)
        {:noreply, %{state | backoff_ms: min(state.backoff_ms * 2, 60_000)}}
    end
  end

  @impl true
  def terminate(_reason, state) do
    _ = deregister(state.config)
    :ok
  end

  defp schedule_heartbeat, do: Process.send_after(self(), :heartbeat, @heartbeat_interval)

  defp register(config) do
    payload = %{
      instance: %{
        instanceId: config.eureka_instance_id,
        hostName: config.eureka_instance_host,
        app: String.upcase(config.eureka_app_name),
        ipAddr: config.eureka_instance_host,
        vipAddress: config.eureka_app_name,
        secureVipAddress: config.eureka_app_name,
        status: "UP",
        port: %{"$" => config.eureka_instance_port, "@enabled" => "true"},
        securePort: %{"$" => 443, "@enabled" => "false"},
        healthCheckUrl: "http://#{config.eureka_instance_host}:#{config.eureka_instance_port}/actuator/health",
        statusPageUrl: "http://#{config.eureka_instance_host}:#{config.eureka_instance_port}/actuator/health",
        homePageUrl: "http://#{config.eureka_instance_host}:#{config.eureka_instance_port}/",
        dataCenterInfo: %{
          "@class" => "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
          name: "MyOwn"
        },
        metadata: %{
          "management.port" => Integer.to_string(config.eureka_instance_port),
          "prometheus.scrape" => "true",
          "prometheus.path" => "/actuator/prometheus"
        }
      }
    }

    request(:post, "#{server_url(config)}/apps/#{config.eureka_app_name}", payload)
  end

  defp heartbeat(config) do
    case request(:put, instance_url(config), "") do
      {:error, {:status, 404}} -> {:error, :not_found}
      other -> other
    end
  end

  defp deregister(config), do: request(:delete, instance_url(config), "")

  defp request(method, url, body) do
    options =
      case body do
        "" -> [method: method, url: url]
        _ -> [method: method, url: url, json: body]
      end

    case Req.request(options) do
      {:ok, %{status: status}} when status in 200..299 -> :ok
      {:ok, %{status: status}} -> {:error, {:status, status}}
      {:error, reason} -> {:error, reason}
    end
  end

  defp server_url(config), do: String.trim_trailing(config.eureka_server_url, "/")
  defp instance_url(config), do: "#{server_url(config)}/apps/#{config.eureka_app_name}/#{config.eureka_instance_id}"
end
