package channel

import (
	"strings"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
)

func TestSnapshotMarkerCheckpointUpdate_수신확인과_다음오프셋을_하나의_파이프라인에_담는다(t *testing.T) {
	t.Parallel()
	marker := SnapshotMarkerReceipt{
		Offset:     41,
		SnapshotID: "93b19168-4a63-49cd-b01d-b8d0667a1cb5",
		Source:     "cowork-channel",
		OccurredAt: time.Date(2026, 8, 26, 1, 2, 3, 0, time.UTC),
	}

	pipeline := snapshotMarkerCheckpointUpdate(
		"cowork-voice.channel-member",
		"channel.member.event",
		2,
		testTopicID,
		42,
		marker,
	)
	encoded, err := bson.MarshalExtJSON(bson.D{{Key: "pipeline", Value: pipeline}}, false, false)
	if err != nil {
		t.Fatalf("marshal update pipeline: %v", err)
	}
	serialized := string(encoded)
	for _, expected := range []string{
		`"next_offset"`,
		`"snapshot_completed_offset"`,
		`"snapshot_id"`,
		`"93b19168-4a63-49cd-b01d-b8d0667a1cb5"`,
		`"snapshot_source"`,
		`"cowork-channel"`,
		`"snapshot_occurred_at"`,
		`"$max"`,
		`"$cond"`,
	} {
		if !strings.Contains(serialized, expected) {
			t.Fatalf("update pipeline missing %s: %s", expected, serialized)
		}
	}
}
