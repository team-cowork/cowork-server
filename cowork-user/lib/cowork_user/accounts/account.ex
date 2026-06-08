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
    field :status_message, :string
    field :status_expires_at, :naive_datetime
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
      :status_message,
      :status_expires_at,
      :created_by,
      :last_modified_by
    ])
    |> validate_required([:id, :name, :email, :sex, :status])
  end

  def profile_update_changeset(account, attrs) do
    account
    |> cast(attrs, [:name, :github, :last_modified_by])
  end

  def status_changeset(account, attrs) do
    account
    |> cast(attrs, [:status, :status_message, :status_expires_at, :last_modified_by])
    |> validate_required([:status])
  end
end
