defmodule CoworkUser.Application do
  use Application

  @impl true
  def start(_type, _args) do
    config = CoworkUser.AppConfig.load()

    children = [
      {CoworkUser.Metrics.Store, []},
      {CoworkUser.Repo, []},
      {CoworkUser.Server, port: config.port},
      {CoworkUser.Eureka.Registrar, config: config},
      {CoworkUser.Kafka.Consumer, config: config}
    ]

    opts = [strategy: :one_for_one, name: CoworkUser.Supervisor]
    Supervisor.start_link(children, opts)
  end
end
