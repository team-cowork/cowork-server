defmodule CoworkUser.MixProject do
  use Mix.Project

  def project do
    [
      app: :cowork_user,
      version: "20260623.0.0",
      elixir: "~> 1.18",
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
      {:plug_cowboy, "~> 2.8"},
      {:jason, "~> 1.4"},
      {:ecto_sql, "~> 3.14"},
      {:myxql, "~> 0.9"},
      {:req, "~> 0.6"},
      {:brod, "~> 4.5"},
      {:ex_aws, "~> 2.7"},
      {:ex_aws_s3, "~> 2.5"},
      {:hackney, "~> 4.4"},
      {:logger_file_backend, "~> 0.0.14"},
      {:sweet_xml, "~> 0.7"},
      {:redix, "~> 1.5"}
    ]
  end
end
