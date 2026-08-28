defmodule CoworkUser.Kafka.ProjectionReadinessTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.ProjectionReadiness

  test "모든 시작 high-watermark를 저장된 checkpoint가 넘어야 ready다" do
    team = {"cowork-user.team-member", "team.member.event", 0}
    presence = {"cowork-user.user-presence", "user.presence.event", 0}
    barriers = %{team => 7, presence => 3}

    refute ProjectionReadiness.barrier_satisfied?(barriers, %{team => 7, presence => 2})
    assert ProjectionReadiness.barrier_satisfied?(barriers, %{team => 7, presence => 3})
  end

  test "checkpoint가 없는 partition은 ready로 오판하지 않는다" do
    partition = {"cowork-user.team-member", "team.member.event", 1}

    refute ProjectionReadiness.barrier_satisfied?(%{partition => 0}, %{})
  end

  test "checkpoint는 Kafka에 보존된 offset 범위 안에 있어야 replay 가능하다" do
    assert ProjectionReadiness.checkpoint_replayable?(5, 5, 9)
    assert ProjectionReadiness.checkpoint_replayable?(9, 5, 9)
    refute ProjectionReadiness.checkpoint_replayable?(4, 5, 9)
    refute ProjectionReadiness.checkpoint_replayable?(10, 5, 9)
  end

  test "consumer 연결 상실은 기존 ready 상태와 무관하게 gate를 닫는다" do
    assert ProjectionReadiness.runtime_connection_action(true, 4, 5, :disconnected) == :close
    assert ProjectionReadiness.runtime_connection_action(false, 5, 5, :disconnected) == :close
  end

  test "consumer 재연결은 새 high-watermark recapture를 요구하고 heartbeat는 중복 capture하지 않는다" do
    assert ProjectionReadiness.runtime_connection_action(false, 5, 5, :connected) == :recapture
    assert ProjectionReadiness.runtime_connection_action(true, 4, 5, :connected) == :recapture
    assert ProjectionReadiness.runtime_connection_action(true, 5, 5, :connected) == :keep
  end

  test "broker 오류만 재캡처하고 topology 또는 보존 범위 불일치는 영구 fail-closed한다" do
    assert ProjectionReadiness.replay_validation_action(:ok) == :keep
    assert ProjectionReadiness.replay_validation_action({:error, :broker_down}) == :recapture

    assert ProjectionReadiness.replay_validation_action({
             :invalid,
             :checkpoint_outside_retained_log
           }) == :halt
  end
end
