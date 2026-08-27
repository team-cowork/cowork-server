defmodule CoworkUser.Accounts.Account do
  use Ecto.Schema
  import Ecto.Changeset

  @custom_status_max_length 30
  @status_message_max_length 100

  @primary_key {:id, :integer, autogenerate: false}
  schema "tb_accounts" do
    field(:name, :string)
    field(:email, :string)
    field(:sex, :string)
    field(:github, :string)
    field(:description, :string)
    field(:student_role, :string)
    field(:student_number, :string)
    field(:datagsm_student_id, :integer)
    field(:datagsm_updated_at, :utc_datetime_usec)
    field(:major, :string)
    field(:specialty, :string)
    field(:status, :string)
    field(:custom_status, :string)
    field(:status_message, :string)
    field(:status_expires_at, :utc_datetime)
    field(:presence_updated_at, :utc_datetime_usec)
    field(:created_by, :integer)
    field(:last_modified_by, :integer)

    has_one(:profile, CoworkUser.Accounts.Profile, foreign_key: :account_id)

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
      :datagsm_student_id,
      :major,
      :specialty,
      :status,
      :custom_status,
      :status_message,
      :status_expires_at,
      :presence_updated_at,
      :created_by,
      :last_modified_by
    ])
    |> validate_required([:id, :name, :email, :sex, :status])
    |> validate_status_text_lengths()
  end

  def profile_update_changeset(account, attrs) do
    account
    |> cast(attrs, [:name, :github, :last_modified_by])
    |> validate_required([:name])
  end

  def custom_status_changeset(account, attrs) do
    account
    |> cast(attrs, [
      :custom_status,
      :status_message,
      :status_expires_at,
      :last_modified_by
    ])
    |> validate_required([:custom_status])
    |> validate_status_text_lengths()
  end

  def custom_status_max_length, do: @custom_status_max_length
  def status_message_max_length, do: @status_message_max_length

  def student_role_changeset(account, attrs) do
    account
    |> cast(attrs, [:student_role, :last_modified_by])
    |> validate_required([:student_role])
  end

  def datagsm_sync_changeset(account, attrs) do
    account
    |> cast(attrs, [
      :name,
      :email,
      :sex,
      :github,
      :student_role,
      :student_number,
      :major,
      :specialty,
      :datagsm_updated_at,
      :last_modified_by
    ])
    |> validate_required([:student_role])
  end

  defp validate_status_text_lengths(changeset) do
    changeset
    |> validate_length(:custom_status, max: @custom_status_max_length)
    |> validate_length(:status_message, max: @status_message_max_length)
  end
end
