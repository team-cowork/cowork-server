defmodule CoworkUser.TeamMembershipProjectionTest do
  use ExUnit.Case, async: true

  alias CoworkUser.TeamMembershipProjection

  defmodule RecordingStore do
    def apply(event) do
      send(self(), {:applied, event})
      :ok
    end

    def member_ids_for_requester(team_id, requester_user_id) do
      send(self(), {:queried, team_id, requester_user_id})

      if requester_user_id == 7 do
        {:ok, [7, 9]}
      else
        {:error, :forbidden}
      end
    end
  end

  test "UPSERT 이벤트를 정규화해 저장소에 적용한다" do
    payload = %{
      "eventType" => "UPSERT",
      "teamId" => 11,
      "userId" => 22,
      "role" => "ADMIN",
      "teamName" => "platform",
      "occurredAt" => "2026-08-26T12:34:56.123456Z"
    }

    assert :ok = TeamMembershipProjection.apply_event(payload, RecordingStore)

    assert_receive {:applied,
                    %{
                      event_type: "UPSERT",
                      team_id: 11,
                      user_id: 22,
                      role: "ADMIN",
                      team_name: "platform",
                      occurred_at: ~N[2026-08-26 12:34:56.123456]
                    }}
  end

  test "오프셋 없는 LocalDateTime 이벤트는 거부한다" do
    payload = %{
      "eventType" => "UPSERT",
      "teamId" => 11,
      "userId" => 22,
      "role" => "ADMIN",
      "teamName" => "platform",
      "occurredAt" => "2026-08-26T12:34:56.123456"
    }

    assert {:error, {:invalid_datetime, "occurredAt"}} =
             TeamMembershipProjection.apply_event(payload, RecordingStore)

    refute_receive {:applied, _}
  end

  test "요청자가 활성 멤버이면 팀 필터용 활성 사용자 ID를 반환한다" do
    assert {:ok, [7, 9]} =
             TeamMembershipProjection.member_ids_for_requester("11", "7", RecordingStore)

    assert_receive {:queried, 11, 7}
  end

  test "요청자가 활성 멤버가 아니면 팀 검색을 거부한다" do
    assert {:error, :forbidden} =
             TeamMembershipProjection.member_ids_for_requester("11", "8", RecordingStore)

    assert_receive {:queried, 11, 8}
  end

  test "잘못된 팀 ID는 저장소를 조회하지 않는다" do
    assert {:error, :invalid_team_id} =
             TeamMembershipProjection.member_ids_for_requester("bad", "7", RecordingStore)

    refute_receive {:queried, _, _}
  end

  test "잘못된 요청자 ID는 저장소를 조회하지 않는다" do
    assert {:error, :invalid_requester_user_id} =
             TeamMembershipProjection.member_ids_for_requester("11", "0", RecordingStore)

    refute_receive {:queried, _, _}
  end
end
