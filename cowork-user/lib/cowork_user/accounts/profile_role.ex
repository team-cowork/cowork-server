defmodule CoworkUser.Accounts.ProfileRole do
  use Ecto.Schema
  import Ecto.Changeset

  @primary_key false
  schema "tb_profile_roles" do
    field :profile_id, :integer
    field :role, :string
  end

  def changeset(profile_role, attrs) do
    profile_role
    |> cast(attrs, [:profile_id, :role])
    |> validate_required([:profile_id, :role])
  end
end
