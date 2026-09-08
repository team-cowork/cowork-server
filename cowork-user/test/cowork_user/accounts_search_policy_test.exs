defmodule CoworkUser.AccountsSearchPolicyTest do
  use ExUnit.Case, async: true
  import Ecto.Query

  alias CoworkUser.Accounts
  alias CoworkUser.Accounts.Profile

  defmodule AllowedTeamMembership do
    def member_ids_for_requester(team_id, requester_user_id) do
      send(self(), {:authorized_scope, team_id, requester_user_id})
      {:ok, [7, 9]}
    end
  end

  defmodule ForbiddenTeamMembership do
    def member_ids_for_requester(team_id, requester_user_id) do
      send(self(), {:forbidden_scope, team_id, requester_user_id})
      {:error, :forbidden}
    end
  end

  defmodule UnavailableTeamMembership do
    def member_ids_for_requester(_team_id, _requester_user_id) do
      {:error, {:storage, :database_unavailable}}
    end
  end

  defmodule UnexpectedTeamMembershipLookup do
    def member_ids_for_requester(_team_id, _requester_user_id) do
      raise "teamId가 없으면 팀 소속 정보를 조회하면 안 됩니다."
    end
  end

  describe "validate_search_filters/1" do
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
  end

  describe "authorize_team_search/3" do
    test "teamId가 없으면 기존 검색 필터를 그대로 보존한다" do
      params = %{"q" => "kim", "status" => "online"}

      assert {:ok, ^params} =
               Accounts.authorize_team_search(7, params, UnexpectedTeamMembershipLookup)
    end

    test "활성 팀 멤버이면 검색 범위를 같은 팀원 ID로 제한한다" do
      params = %{"teamId" => "11", "q" => "kim", "user_ids" => "999"}

      assert {:ok, scoped_params} =
               Accounts.authorize_team_search(7, params, AllowedTeamMembership)

      assert scoped_params == %{"teamId" => "11", "q" => "kim", "user_ids" => [7, 9]}
      assert_receive {:authorized_scope, "11", 7}
    end

    test "활성 팀 멤버가 아니면 팀 검색을 거부한다" do
      assert {:error, :forbidden} =
               Accounts.authorize_team_search(
                 8,
                 %{"teamId" => "11"},
                 ForbiddenTeamMembership
               )

      assert_receive {:forbidden_scope, "11", 8}
    end

    test "팀 소속 정보 저장소 오류는 서비스 불가 오류로 구분한다" do
      assert {:error, {:team_projection, {:storage, :database_unavailable}}} =
               Accounts.authorize_team_search(
                 7,
                 %{"teamId" => "11"},
                 UnavailableTeamMembership
               )
    end
  end

  describe "normalize_search_term/1" do
    test "앞뒤 공백을 제거한 검색어를 사용한다" do
      assert Accounts.normalize_search_term("  김코워크  ") == "김코워크"
    end

    test "공백만 있는 검색어는 필터를 적용하지 않는다" do
      assert Accounts.normalize_search_term("") == nil
      assert Accounts.normalize_search_term("   ") == nil
      assert Accounts.normalize_search_term("\t\n") == nil
    end

    test "문자열이 아닌 값은 필터를 적용하지 않는다" do
      assert Accounts.normalize_search_term(nil) == nil
      assert Accounts.normalize_search_term(["kim"]) == nil
    end
  end

  describe "like_pattern/1" do
    test "부분 일치를 위해 앞뒤에 와일드카드를 붙인다" do
      assert Accounts.like_pattern("kim") == "%kim%"
    end

    test "%와 _는 LIKE 연산자로 주입되지 않게 escape 한다" do
      assert Accounts.like_pattern("100%") == "%100\\%%"
      assert Accounts.like_pattern("a_b") == "%a\\_b%"
    end

    test "백슬래시 자체도 escape 해 escape 문자를 주입할 수 없게 한다" do
      assert Accounts.like_pattern("a\\b") == "%a\\\\b%"
      assert Accounts.like_pattern("\\%") == "%\\\\\\%%"
    end

    test "대소문자와 한글은 변환하지 않고 그대로 보존한다" do
      assert Accounts.like_pattern("KimCowork") == "%KimCowork%"
      assert Accounts.like_pattern("김코워크") == "%김코워크%"
    end
  end

  describe "search_term/2" do
    test "q를 통합 검색어로 사용한다" do
      assert Accounts.search_term("  kim  ", nil) == "kim"
    end

    test "q가 없으면 호환 alias인 query를 사용한다" do
      assert Accounts.search_term(nil, "kim") == "kim"
    end

    test "q가 공백뿐이면 query로 넘어간다" do
      assert Accounts.search_term("   ", "kim") == "kim"
    end

    test "q와 query가 모두 있으면 q가 우선한다" do
      assert Accounts.search_term("kim", "lee") == "kim"
    end

    test "둘 다 비어 있으면 통합 검색 필터를 적용하지 않는다" do
      assert Accounts.search_term(nil, nil) == nil
      assert Accounts.search_term("", " ") == nil
    end
  end

  defp base_query, do: from(p in Profile, join: a in assoc(p, :account))

  describe "maybe_query/2" do
    test "검색어가 없으면 쿼리를 그대로 둔다" do
      query = base_query()
      assert Accounts.maybe_query(query, nil) == query
    end

    test "MySQL이 지원하지 않는 ilike가 아니라 like로 이름·닉네임을 검색한다" do
      inspected = Accounts.maybe_query(base_query(), "kim") |> inspect()

      assert inspected =~ "like("
      refute inspected =~ "ilike("
    end
  end
end
