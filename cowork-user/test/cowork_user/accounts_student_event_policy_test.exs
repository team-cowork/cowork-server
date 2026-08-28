defmodule CoworkUser.AccountsStudentEventPolicyTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Accounts

  test "저장 시각이 없거나 더 최신인 DataGSM 이벤트만 적용한다" do
    current = ~U[2026-08-26 01:02:03.123456Z]
    newer = ~U[2026-08-26 01:02:03.123457Z]
    older = ~U[2026-08-26 01:02:03.123455Z]

    assert Accounts.student_event_newer?(nil, current)
    assert Accounts.student_event_newer?(current, newer)
    refute Accounts.student_event_newer?(current, current)
    refute Accounts.student_event_newer?(current, older)
  end
end
