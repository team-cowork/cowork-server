defmodule CoworkUser.KafkaProjectionReadinessTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.ProjectionReadiness

  @generation 16
  @token "45985bb2-8689-45c0-b23b-399e754e1e0c"
  @snapshot_id "e71bf570-06e2-443c-9a9c-c0cdd666ba2d"

  defp range(end_offset, marker_offset) do
    %{
      beginning_offset: 0,
      end_offset: end_offset,
      replay_generation: @generation,
      validated_marker: %{
        marker_offset: marker_offset,
        replay_generation: @generation,
        replay_token: @token,
        snapshot_id: @snapshot_id
      }
    }
  end

  defp checkpoint(next_offset, marker_offset) do
    %{
      next_offset: next_offset,
      replay_generation: @generation,
      replay_token: @token,
      replay_lease_active: true,
      invalid_record_offset: nil,
      snapshot_completed_offset: marker_offset,
      last_snapshot_id: @snapshot_id
    }
  end

  defp marker(marker_offset) do
    %{
      marker_offset: marker_offset,
      replay_generation: @generation,
      replay_token: @token,
      snapshot_id: @snapshot_id
    }
  end

  describe "partition_ready?/3" do
    test "stays ready while the source publishes past the snapshot marker" do
      assert ProjectionReadiness.partition_ready?(
               range(1120, 1114),
               checkpoint(1115, 1114),
               marker(1114)
             )
    end

    test "is ready once the checkpoint reaches the live high-watermark" do
      assert ProjectionReadiness.partition_ready?(
               range(1115, 1114),
               checkpoint(1115, 1114),
               marker(1114)
             )
    end

    test "is not ready while the checkpoint trails the snapshot marker" do
      refute ProjectionReadiness.partition_ready?(
               range(1120, 1114),
               checkpoint(1114, 1114),
               marker(1114)
             )
    end

    test "rejects a checkpoint beyond the retained log" do
      refute ProjectionReadiness.partition_ready?(
               range(1115, 1114),
               checkpoint(1116, 1114),
               marker(1114)
             )
    end

    test "rejects a marker left behind by an older replay generation" do
      refute ProjectionReadiness.partition_ready?(
               range(1120, 1114),
               checkpoint(1115, 1114),
               %{marker(1114) | replay_generation: @generation - 1}
             )
    end

    test "rejects a checkpoint latched on an invalid record" do
      refute ProjectionReadiness.partition_ready?(
               range(1120, 1114),
               %{checkpoint(1115, 1114) | invalid_record_offset: 1113},
               marker(1114)
             )
    end

    test "rejects a checkpoint whose replay lease expired" do
      refute ProjectionReadiness.partition_ready?(
               range(1120, 1114),
               %{checkpoint(1115, 1114) | replay_lease_active: false},
               marker(1114)
             )
    end
  end
end
