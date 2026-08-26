defmodule CoworkUser.Kafka.ProfileOutboxRelayTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.ProfileOutboxRelay

  test "확인된 레코드만 id 순서로 제거한다" do
    owner = self()
    records = [%{id: 1}, %{id: 2}]

    assert :ok =
             ProfileOutboxRelay.dispatch_records(
               records,
               fn record ->
                 send(owner, {:published, record.id})
                 :ok
               end,
               fn record ->
                 send(owner, {:acknowledged, record.id})
                 :ok
               end,
               fn _record, _reason -> flunk("successful record must not be marked failed") end
             )

    assert_receive {:published, 1}
    assert_receive {:acknowledged, 1}
    assert_receive {:published, 2}
    assert_receive {:acknowledged, 2}
  end

  test "발행 실패 시 해당 레코드를 보존하고 뒤 레코드를 발행하지 않는다" do
    owner = self()
    records = [%{id: 1}, %{id: 2}]

    assert {:error, {:publish, 1, :broker_unavailable}} =
             ProfileOutboxRelay.dispatch_records(
               records,
               fn record ->
                 send(owner, {:published, record.id})
                 {:error, :broker_unavailable}
               end,
               fn record ->
                 send(owner, {:acknowledged, record.id})
                 :ok
               end,
               fn record, reason -> send(owner, {:failed, record.id, reason}) end
             )

    assert_receive {:published, 1}
    assert_receive {:failed, 1, :broker_unavailable}
    refute_receive {:acknowledged, _id}
    refute_receive {:published, 2}
  end

  test "Kafka 확인 뒤 outbox 제거 실패 시 뒤 레코드로 진행하지 않는다" do
    owner = self()
    records = [%{id: 1}, %{id: 2}]

    assert {:error, {:acknowledge, 1, :database_unavailable}} =
             ProfileOutboxRelay.dispatch_records(
               records,
               fn record ->
                 send(owner, {:published, record.id})
                 :ok
               end,
               fn record ->
                 send(owner, {:acknowledged, record.id})
                 {:error, :database_unavailable}
               end,
               fn _record, _reason -> :ok end
             )

    assert_receive {:published, 1}
    assert_receive {:acknowledged, 1}
    refute_receive {:published, 2}
  end
end
