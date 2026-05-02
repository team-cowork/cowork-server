defmodule CoworkUser.MixProject do
  use Mix.Project

  def project do
    [
      app: :cowork_user,
      version: "0.1.0",
      elixir: "~> 1.17",
      start_permanent: Mix.env() == :prod,
      deps: deps()
    ]
  end

  def application do
    [
      extra_applications: [:logger, :runtime_tools, :ssl, :inets, :crypto, :brod],
      mod: {CoworkUser.Application, []}
    ]
  end

  defp deps do
    [
      {:plug_cowboy, "~> 2.7"},
      {:jason, "~> 1.4"},
      {:ecto_sql, "~> 3.12"},
      {:myxql, "~> 0.7"},
      {:req, "~> 0.5"},
      {:brod, "~> 4.5"},
      {:ex_aws, "~> 2.5"},
      {:ex_aws_s3, "~> 2.5"},
      {:hackney, "~> 1.23"},
      {:logger_file_backend, "~> 0.0.14"},
      {:sweet_xml, "~> 0.7"}
    ]
  end
end
