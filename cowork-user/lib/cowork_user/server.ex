defmodule CoworkUser.Server do
  use Supervisor

  def start_link(opts) do
    Supervisor.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @impl true
  def init(opts) do
    port = Keyword.fetch!(opts, :port)

    children = [
      {Plug.Cowboy, scheme: :http, plug: CoworkUser.Router, options: [port: port]}
    ]

    Supervisor.init(children, strategy: :one_for_one)
  end
end
