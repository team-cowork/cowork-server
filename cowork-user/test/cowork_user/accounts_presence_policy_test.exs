defmodule CoworkUser.AccountsPresencePolicyTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Accounts
  alias CoworkUser.Accounts.Account
  alias CoworkUser.OpenAPI

  test "login upsert preserves an existing authoritative presence projection" do
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

  test "first rollout resets accounts without an authoritative projection to offline baseline" do
    add_column_migration =
      File.read!(
        Path.expand(
          "../../src/main/resources/db/migration/V13__add_custom_status.sql",
          __DIR__
        )
      )

    baseline_migration =
      File.read!(
        Path.expand(
          "../../src/main/resources/db/migration/V14__initialize_presence_projection_baseline.sql",
          __DIR__
        )
      )

    assert add_column_migration =~ "ADD COLUMN custom_status"
    assert baseline_migration =~ "SET custom_status = status"
    assert baseline_migration =~ "WHERE status NOT IN ('online', 'offline')"
    assert baseline_migration =~ "JOIN tb_user_presence_projections"
    assert baseline_migration =~ "account.status = presence.status"
    assert baseline_migration =~ "account.presence_updated_at = presence.event_occurred_at"
    assert baseline_migration =~ "LEFT JOIN tb_user_presence_projections"
    assert baseline_migration =~ "account.status = 'offline'"

    assert baseline_migration =~
             "account.presence_updated_at = '1970-01-01 00:00:00.000000'"

    assert baseline_migration =~ "WHERE presence.user_id IS NULL"
  end

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

  test "OpenAPI exposes custom status separately from authoritative presence" do
    operation = OpenAPI.spec().paths["/users/me/status"].patch
    request_schema = operation.requestBody.content["application/json"].schema
    gateway_response_schema = operation.responses["200"].content["application/json"].schema
    response_schema = gateway_response_schema.properties.data

    assert request_schema.required == ["custom_status"]
    assert Map.has_key?(request_schema.properties, :custom_status)
    assert request_schema.properties.custom_status.maxLength == 30
    assert request_schema.properties.message.maxLength == 100
    refute Map.has_key?(request_schema.properties, :status)
    assert gateway_response_schema.required == ["status", "code", "message", "data"]
    assert gateway_response_schema.properties.status.enum == ["OK"]
    assert gateway_response_schema.properties.code.enum == [200]
    assert response_schema.properties.status.enum == ["online", "offline"]
    assert response_schema.properties.custom_status.nullable
    assert response_schema.properties.custom_status.maxLength == 30
    assert response_schema.properties.status_message.maxLength == 100

    search_parameters = OpenAPI.spec().paths["/users/search"].get.parameters
    status_filter = Enum.find(search_parameters, &(&1.name == "status"))
    custom_status_filter = Enum.find(search_parameters, &(&1.name == "custom_status"))
    assert status_filter.schema.enum == ["online", "offline"]
    assert custom_status_filter.schema.type == "string"
    assert custom_status_filter.schema.maxLength == 30
  end
end
