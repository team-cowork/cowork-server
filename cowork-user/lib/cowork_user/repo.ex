defmodule CoworkUser.Repo do
  use Ecto.Repo,
    otp_app: :cowork_user,
    adapter: Ecto.Adapters.MyXQL
end
