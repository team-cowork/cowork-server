defmodule CoworkUser.AccountsProfilePatchPolicyTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Accounts

  describe "build_profile_update_plan/1 — nickname/description(nullable optional)" do
    test "미제공 필드는 변경 대상에서 빠진다" do
      assert {:ok, %{profile_changes: profile_changes}} = Accounts.build_profile_update_plan(%{})
      refute Map.has_key?(profile_changes, :nickname)
      refute Map.has_key?(profile_changes, :description)
    end

    test "명시적 null은 값을 비우는 변경으로 반영된다" do
      assert {:ok, %{profile_changes: profile_changes}} =
               Accounts.build_profile_update_plan(%{"nickname" => nil, "description" => nil})

      assert profile_changes == %{nickname: nil, description: nil}
    end

    test "값을 제공하면 그 값으로 변경한다" do
      assert {:ok, %{profile_changes: profile_changes}} =
               Accounts.build_profile_update_plan(%{
                 "nickname" => "새 별명",
                 "description" => "새 소개"
               })

      assert profile_changes == %{nickname: "새 별명", description: "새 소개"}
    end

    test "한 필드만 제공하면 다른 필드는 변경 대상에 포함되지 않는다" do
      assert {:ok, %{profile_changes: profile_changes}} =
               Accounts.build_profile_update_plan(%{"nickname" => "새 별명"})

      assert profile_changes == %{nickname: "새 별명"}
    end
  end

  describe "build_profile_update_plan/1 — name(required-on-provide)" do
    test "미제공이면 변경하지 않는다" do
      assert {:ok, %{account_changes: account_changes}} = Accounts.build_profile_update_plan(%{})
      refute Map.has_key?(account_changes, :name)
    end

    test "명시적 null은 유효성 오류로 거부한다" do
      assert {:error, {:validation, _message}} =
               Accounts.build_profile_update_plan(%{"name" => nil})
    end

    test "값을 제공하면 그 값으로 변경한다" do
      assert {:ok, %{account_changes: %{name: "새 이름"}}} =
               Accounts.build_profile_update_plan(%{"name" => "새 이름"})
    end
  end

  describe "build_profile_update_plan/1 — github_id(nullable optional)" do
    test "미제공이면 변경하지 않는다" do
      assert {:ok, %{account_changes: account_changes}} = Accounts.build_profile_update_plan(%{})
      refute Map.has_key?(account_changes, :github)
    end

    test "명시적 null은 값을 비운다" do
      assert {:ok, %{account_changes: %{github: nil}}} =
               Accounts.build_profile_update_plan(%{"github_id" => nil})
    end

    test "값을 제공하면 그 값으로 변경한다" do
      assert {:ok, %{account_changes: %{github: "octocat"}}} =
               Accounts.build_profile_update_plan(%{"github_id" => "octocat"})
    end
  end

  describe "build_profile_update_plan/1 — roles" do
    test "미제공이면 기존 목록을 유지한다" do
      assert {:ok, %{roles: :keep}} = Accounts.build_profile_update_plan(%{})
    end

    test "명시적 null은 배열 형식 오류로 거부한다" do
      assert {:error, {:validation, _message}} =
               Accounts.build_profile_update_plan(%{"roles" => nil})
    end

    test "배열이 아닌 값은 오류로 거부한다" do
      assert {:error, {:validation, _message}} =
               Accounts.build_profile_update_plan(%{"roles" => "member"})
    end

    test "빈 배열은 전체 해제로 계획된다" do
      assert {:ok, %{roles: {:replace, []}}} =
               Accounts.build_profile_update_plan(%{"roles" => []})
    end

    test "비어 있지 않은 배열은 정규화되어 전체 교체로 계획된다" do
      assert {:ok, %{roles: {:replace, ["ADMIN", "MEMBER"]}}} =
               Accounts.build_profile_update_plan(%{"roles" => ["MEMBER", "ADMIN", "MEMBER"]})
    end
  end

  describe "build_profile_update_plan/1 — 조합" do
    test "완전히 미제공된 요청은 모든 필드를 유지하는 계획을 만든다" do
      assert {:ok,
              %{
                profile_changes: %{},
                account_changes: %{},
                roles: :keep
              }} = Accounts.build_profile_update_plan(%{})
    end

    test "일부 필드만 제공해도 나머지 필드의 기존 값을 건드리지 않는다" do
      assert {:ok, plan} = Accounts.build_profile_update_plan(%{"name" => "새 이름"})

      assert plan.profile_changes == %{}
      assert plan.account_changes == %{name: "새 이름"}
      assert plan.roles == :keep
    end
  end
end
