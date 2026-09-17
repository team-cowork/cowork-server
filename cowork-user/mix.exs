defmodule CoworkUser.MixProject do
  use Mix.Project

  def project do
    [
      app: :cowork_user,
      version: "20260912.0.0",
      elixir: "~> 1.20",
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
      {:plug_cowboy, "~> 2.9"},
      {:cowlib, "~> 2.20"},
      {:jason, "~> 1.4"},
      {:ecto_sql, "~> 3.14"},
      {:myxql, "~> 0.9"},
      {:req, "~> 0.7"},
      {:brod, "~> 4.6"},
      {:ex_aws, "~> 2.7"},
      {:ex_aws_s3, "~> 2.5"},
      {:hackney, "~> 4.7"},
      {:logger_file_backend, "~> 0.1"},
      {:sweet_xml, "~> 0.7"},
      {:redix, "~> 1.9"}
    ]
  end
end
