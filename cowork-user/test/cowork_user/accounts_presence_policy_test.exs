defmodule CoworkUser.AccountsPresencePolicyTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Accounts
  alias CoworkUser.Accounts.Account

  describe "presence_attrs_for_upsert/1" do
    test "login upsert preserves an existing authoritative online state" do
      occurred_at = ~U[2026-08-26 01:02:03.456789Z]

      assert %{
               status: "offline",
               presence_updated_at: ^occurred_at
             } =
               Accounts.presence_attrs_for_upsert(%{
                 status: "offline",
                 presence_updated_at: occurred_at
               })
    end

    test "new account starts at an old offline baseline instead of trusting login status" do
      assert %{status: "offline", presence_updated_at: ~U[1970-01-01 00:00:00.000000Z]} =
               Accounts.presence_attrs_for_upsert(nil)
    end
  end

  describe "custom status policy" do
    test "custom status mutation cannot overwrite authoritative presence fields" do
      attrs =
        Accounts.custom_status_attrs(42, %{
          "custom_status" => "DO_NOT_DISTURB",
          "message" => "집중 중",
          "expiresAt" => "2026-08-26T18:00:00Z"
        })

      assert attrs.custom_status == "DO_NOT_DISTURB"
      assert attrs.status_message == "집중 중"
      assert attrs.status_expires_at == "2026-08-26T18:00:00Z"
      assert attrs.last_modified_by == 42
      refute Map.has_key?(attrs, :status)
      refute Map.has_key?(attrs, :presence_updated_at)

      changeset =
        Account.custom_status_changeset(%Account{}, %{
          custom_status: "DO_NOT_DISTURB",
          status: "online",
          presence_updated_at: ~U[2026-08-26 18:00:00.000000Z]
        })

      assert Ecto.Changeset.get_change(changeset, :custom_status) == "DO_NOT_DISTURB"
      refute Ecto.Changeset.get_change(changeset, :status)
      refute Ecto.Changeset.get_change(changeset, :presence_updated_at)
    end

    test "custom status와 message는 DB VARCHAR 길이를 넘기 전에 validation error가 된다" do
      changeset =
        Account.custom_status_changeset(%Account{}, %{
          custom_status: String.duplicate("a", Account.custom_status_max_length() + 1),
          status_message: String.duplicate("b", Account.status_message_max_length() + 1)
        })

      refute changeset.valid?

      assert {"should be at most %{count} character(s)", custom_opts} =
               changeset.errors[:custom_status]

      assert custom_opts[:count] == 30

      assert {"should be at most %{count} character(s)", message_opts} =
               changeset.errors[:status_message]

      assert message_opts[:count] == 100
    end
  end
end
