defmodule CoworkUser.Accounts.Profile do
  use Ecto.Schema
  import Ecto.Changeset

  schema "tb_profiles" do
    field :profile_image_key, :string
    field :nickname, :string
    field :description, :string
    field :created_by, :integer
    field :last_modified_by, :integer

    belongs_to :account, CoworkUser.Accounts.Account
    has_many :profile_roles, CoworkUser.Accounts.ProfileRole, foreign_key: :profile_id

    timestamps(type: :utc_datetime_usec, inserted_at: :created_at, updated_at: :updated_at)
  end

  def changeset(profile, attrs) do
    profile
    |> cast(attrs, [
      :account_id,
      :profile_image_key,
      :nickname,
      :description,
      :created_by,
      :last_modified_by
    ])
    |> validate_required([:account_id])
  end
end
