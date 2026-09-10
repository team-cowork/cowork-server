defmodule CoworkUser.AccountsProfilePatchPolicyTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Accounts

  describe "profile_patch/1" do
    test "provided profile fields do not reset omitted fields" do
      assert {:ok,
              %{
                profile: %{description: "새 소개"},
                account: %{},
                roles: :unchanged
              }} = Accounts.profile_patch(%{"description" => "새 소개"})
    end

    test "explicit null clears nullable fields while omitted fields stay absent" do
      assert {:ok,
              %{
                profile: %{nickname: nil, description: nil},
                account: %{github: nil},
                roles: :unchanged
              }} =
               Accounts.profile_patch(%{
                 "nickname" => nil,
                 "description" => nil,
                 "github_id" => nil
               })
    end

    test "name update does not include profile fields or roles" do
      assert {:ok,
              %{
                profile: %{},
                account: %{name: "새 이름"},
                roles: :unchanged
              }} = Accounts.profile_patch(%{"name" => "새 이름"})
    end

    test "omitted roles stay unchanged and an empty array requests full removal" do
      assert {:ok, %{roles: :unchanged}} = Accounts.profile_patch(%{})
      assert {:ok, %{roles: {:replace, []}}} = Accounts.profile_patch(%{"roles" => []})
    end

    test "roles are trimmed, deduplicated, and sorted" do
      assert {:ok, %{roles: {:replace, ["ADMIN", "MEMBER"]}}} =
               Accounts.profile_patch(%{
                 "roles" => [" MEMBER ", "ADMIN", "MEMBER", ""]
               })
    end

    test "null, non-array, and non-string role values are rejected" do
      assert {:error, {:validation, "roles는 배열이어야 합니다."}} =
               Accounts.profile_patch(%{"roles" => nil})

      assert {:error, {:validation, "roles는 배열이어야 합니다."}} =
               Accounts.profile_patch(%{"roles" => "ADMIN"})

      assert {:error, {:validation, "roles의 각 값은 문자열이어야 합니다."}} =
               Accounts.profile_patch(%{"roles" => ["ADMIN", 1]})
    end
  end
end
