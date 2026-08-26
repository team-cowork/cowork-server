defmodule CoworkUser.Kafka.ProjectionQuarantineTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.{ProjectionProcessor, ProjectionQuarantine}

  test "invalid domain and barrier contracts are quarantined while storage failures retry" do
    assert {:quarantine, :invalid_status} =
             ProjectionProcessor.classify({:error, :invalid_status})

    assert {:quarantine, :invalid_barrier_source} =
             ProjectionProcessor.classify({:error, :invalid_barrier_source})

    assert {:retry, :connection_lost} =
             ProjectionProcessor.classify({:error, {:storage, :connection_lost}})

    assert :applied = ProjectionProcessor.classify(:ok)
  end

  test "quarantine entry preserves the raw Kafka key and payload" do
    entry =
      ProjectionQuarantine.entry(
        "cowork-user-presence",
        "user.presence.event",
        2,
        99,
        %{key: <<0, 255>>, payload: <<255, 0, 1>>},
        {:invalid_datetime, "occurredAt"}
      )

    assert entry.consumer_group == "cowork-user-presence"
    assert entry.topic == "user.presence.event"
    assert entry.partition == 2
    assert entry.offset == 99
    assert entry.key == <<0, 255>>
    assert entry.payload == <<255, 0, 1>>
    assert entry.reason == ~s({:invalid_datetime, "occurredAt"})
  end

  test "quarantine migration defines a durable per-record uniqueness contract" do
    migration =
      File.read!(
        Path.expand(
          "../../../src/main/resources/db/migration/V12__add_kafka_projection_quarantine.sql",
          __DIR__
        )
      )

    assert migration =~ "CREATE TABLE tb_kafka_projection_quarantine"
    assert migration =~ "UNIQUE (consumer_group, topic_name, partition_id, record_offset)"
    assert migration =~ "payload        LONGBLOB"
  end
end
