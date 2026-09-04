defmodule CoworkUser.AccountsStudentEventValidationTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Accounts

  describe "apply_student_event/1 입력 형태 검증 (DB 접근 이전에 거부되는 경로)" do
    test "datagsm_student_id가 없으면 형식 오류로 거부한다" do
      assert Accounts.apply_student_event(%{"student_role" => "STUDENT"}) ==
               {:error, :invalid_student_event}
    end

    test "datagsm_student_id가 정수가 아니면 형식 오류로 거부한다" do
      assert Accounts.apply_student_event(%{
               "datagsm_student_id" => "42",
               "student_role" => "STUDENT"
             }) == {:error, :invalid_student_event}
    end

    test "맵이 아닌 이벤트는 형식 오류로 거부한다" do
      assert Accounts.apply_student_event("not a map") == {:error, :invalid_student_event}
      assert Accounts.apply_student_event(nil) == {:error, :invalid_student_event}
      assert Accounts.apply_student_event([]) == {:error, :invalid_student_event}
    end

    test "student_role과 role이 모두 없으면 형식 오류로 거부한다" do
      assert Accounts.apply_student_event(%{"datagsm_student_id" => 42}) ==
               {:error, :invalid_student_event}
    end

    test "student_role이 빈 문자열이면 형식 오류로 거부한다" do
      assert Accounts.apply_student_event(%{
               "datagsm_student_id" => 42,
               "student_role" => ""
             }) == {:error, :invalid_student_event}
    end

    test "role 키만 있어도 student_role 대체값으로 허용한다 (occurred_at 누락 시 형식 오류)" do
      assert Accounts.apply_student_event(%{
               "datagsm_student_id" => 42,
               "role" => "STUDENT"
             }) == {:error, :invalid_student_event}
    end

    test "occurred_at/occurredAt이 없으면 형식 오류로 거부한다" do
      assert Accounts.apply_student_event(%{
               "datagsm_student_id" => 42,
               "student_role" => "STUDENT"
             }) == {:error, :invalid_student_event}
    end

    test "occurred_at이 ISO8601 형식이 아니면 형식 오류로 거부한다" do
      assert Accounts.apply_student_event(%{
               "datagsm_student_id" => 42,
               "student_role" => "STUDENT",
               "occurred_at" => "not-a-timestamp"
             }) == {:error, :invalid_student_event}
    end
  end
end
