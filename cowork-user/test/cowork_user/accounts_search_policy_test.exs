defmodule CoworkUser.AccountsSearchPolicyTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Accounts

  defmodule AllowedProjection do
    def member_ids_for_requester(team_id, requester_user_id) do
      send(self(), {:authorized_scope, team_id, requester_user_id})
      {:ok, [7, 9]}
    end
  end

  defmodule ForbiddenProjection do
    def member_ids_for_requester(team_id, requester_user_id) do
      send(self(), {:forbidden_scope, team_id, requester_user_id})
      {:error, :forbidden}
    end
  end

  defmodule UnavailableProjection do
    def member_ids_for_requester(_team_id, _requester_user_id) do
      {:error, {:storage, :database_unavailable}}
    end
  end

  defmodule UnexpectedProjection do
    def member_ids_for_requester(_team_id, _requester_user_id) do
      raise "teamId가 없으면 팀 투영을 조회하면 안 됩니다."
    end
  end

  test "teamId가 없으면 기존 검색 필터를 그대로 보존한다" do
    params = %{"q" => "kim", "status" => "online"}

    assert {:ok, ^params} = Accounts.authorize_team_search(7, params, UnexpectedProjection)
  end

  test "status 검색 필터는 authoritative presence enum만 허용한다" do
    assert :ok = Accounts.validate_search_filters(%{})
    assert :ok = Accounts.validate_search_filters(%{"status" => "online"})
    assert :ok = Accounts.validate_search_filters(%{"status" => "offline"})

    assert {:error, {:validation, message}} =
             Accounts.validate_search_filters(%{"status" => "busy"})

    assert message =~ "online"
    assert message =~ "offline"
  end

  test "custom_status 검색 필터는 OpenAPI와 같은 30자 경계를 적용한다" do
    assert :ok =
             Accounts.validate_search_filters(%{
               "custom_status" => String.duplicate("가", 30)
             })

    assert {:error, {:validation, message}} =
             Accounts.validate_search_filters(%{
               "custom_status" => String.duplicate("가", 31)
             })

    assert message =~ "30"
  end

  test "활성 팀 멤버이면 검색 범위를 투영된 팀원 ID로 제한한다" do
    params = %{"teamId" => "11", "q" => "kim", "user_ids" => "999"}

    assert {:ok, scoped_params} =
             Accounts.authorize_team_search(7, params, AllowedProjection)

    assert scoped_params == %{"teamId" => "11", "q" => "kim", "user_ids" => [7, 9]}
    assert_receive {:authorized_scope, "11", 7}
  end

  test "활성 팀 멤버가 아니면 팀 검색을 거부한다" do
    assert {:error, :forbidden} =
             Accounts.authorize_team_search(
               8,
               %{"teamId" => "11"},
               ForbiddenProjection
             )

    assert_receive {:forbidden_scope, "11", 8}
  end

  test "팀 투영 저장소 오류는 서비스 불가 오류로 구분한다" do
    assert {:error, {:team_projection, {:storage, :database_unavailable}}} =
             Accounts.authorize_team_search(
               7,
               %{"teamId" => "11"},
               UnavailableProjection
             )
  end
end
