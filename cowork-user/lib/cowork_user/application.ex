defmodule CoworkUser.Application do
  use Application

  @impl true
  def start(_type, _args) do
    config = CoworkUser.AppConfig.load()

    children = [
      {CoworkUser.Metrics.Store, []},
      {CoworkUser.Repo, []},
      {CoworkUser.Kafka.Producer, config: config},
      {CoworkUser.Kafka.ProfileOutboxRelay, config: config},
      {CoworkUser.Kafka.ProfileSnapshotPublisher, config: config},
      {Redix, host: config.redis_host, port: config.redis_port, name: :redix},
      {CoworkUser.Kafka.ProjectionReadiness, config: config},
      {CoworkUser.Kafka.Consumer, config: config},
      {CoworkUser.Server, port: config.port},
      {CoworkUser.Eureka.Registrar, config: config}
    ]

    opts = [strategy: :one_for_one, name: CoworkUser.Supervisor]
    Supervisor.start_link(children, opts)
  end
end
