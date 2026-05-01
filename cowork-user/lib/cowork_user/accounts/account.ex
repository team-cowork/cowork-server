defmodule CoworkUser.Accounts.Account do
  use Ecto.Schema
  import Ecto.Changeset

  @primary_key {:id, :integer, autogenerate: false}
  schema "tb_accounts" do
    field :name, :string
    field :email, :string
    field :sex, :string
    field :github, :string
    field :description, :string
    field :student_role, :string
    field :student_number, :string
    field :major, :string
    field :specialty, :string
    field :status, :string
    field :created_by, :integer
    field :last_modified_by, :integer

    has_one :profile, CoworkUser.Accounts.Profile, foreign_key: :account_id

    timestamps(type: :utc_datetime_usec, inserted_at: :created_at, updated_at: :updated_at)
  end

  def changeset(account, attrs) do
    account
    |> cast(attrs, [
      :id,
      :name,
      :email,
      :sex,
      :github,
      :description,
      :student_role,
      :student_number,
      :major,
      :specialty,
      :status,
      :created_by,
      :last_modified_by
    ])
    |> validate_required([:id, :name, :email, :sex, :status])
  end
end
