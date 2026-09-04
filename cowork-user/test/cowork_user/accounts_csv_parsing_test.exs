defmodule CoworkUser.AccountsCsvParsingTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Accounts

  describe "parse_int_csv/1" do
    test "쉼표로 구분된 양의 정수만 유효한 ID로 파싱한다" do
      assert Accounts.parse_int_csv("1,2,3") == [1, 2, 3]
    end

    test "공백은 트리밍하고 무시한다" do
      assert Accounts.parse_int_csv(" 1 , 2 ,3 ") == [1, 2, 3]
    end

    test "빈 문자열 토큰은 무시한다" do
      assert Accounts.parse_int_csv("1,,2,") == [1, 2]
    end

    test "0 이하 정수는 무시한다" do
      assert Accounts.parse_int_csv("1,0,-5,2") == [1, 2]
    end

    test "정수가 아닌 토큰은 무시한다" do
      assert Accounts.parse_int_csv("1,abc,2,1.5,3x") == [1, 2]
    end

    test "완전히 빈 입력은 빈 목록을 반환한다" do
      assert Accounts.parse_int_csv("") == []
      assert Accounts.parse_int_csv(",,,") == []
    end

    test "허용된 토큰 수(1000개)를 넘는 초과분은 파싱하지 않고 버린다" do
      ids = 1..1000 |> Enum.to_list()
      csv = Enum.join(ids ++ [1001, 1002], ",")

      result = Accounts.parse_int_csv(csv)

      assert length(result) == 1000
      assert result == ids
      refute 1001 in result
      refute 1002 in result
    end

    test "중복 ID는 중복 그대로 보존한다 (호출부에서 필요 시 dedup)" do
      assert Accounts.parse_int_csv("1,1,1") == [1, 1, 1]
    end
  end
end
