package kafka

import (
	"encoding/json"
	"reflect"
	"regexp"
	"strconv"
	"testing"
	"time"

	"github.com/cowork/authorization/internal/domain"
	segkafka "github.com/segmentio/kafka-go"
)

func TestNewSnapshotIDReturnsRFC4122Version4(t *testing.T) {
	t.Parallel()

	id, err := newSnapshotID()
	if err != nil {
		t.Fatalf("newSnapshotID() error = %v", err)
	}
	pattern := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	if !pattern.MatchString(id) {
		t.Fatalf("newSnapshotID() = %q, want RFC 4122 version 4 UUID", id)
	}
}

func TestNormalizeSnapshotPartitionsRequiresCompleteUniqueTopology(t *testing.T) {
	t.Parallel()

	partitions, err := normalizeSnapshotPartitions([]int{2, 0, 1})
	if err != nil {
		t.Fatalf("normalizeSnapshotPartitions() error = %v", err)
	}
	if !reflect.DeepEqual(partitions, []int{0, 1, 2}) {
		t.Fatalf("partitions = %v, want sorted topology", partitions)
	}
	for _, invalid := range [][]int{nil, {-1}, {0, 0}} {
		if _, err := normalizeSnapshotPartitions(invalid); err == nil {
			t.Fatalf("normalizeSnapshotPartitions(%v) error = nil", invalid)
		}
	}
}

func TestExplicitSnapshotPartitionNeverFallsBackToHashing(t *testing.T) {
	t.Parallel()

	balancer := &partitionAwareBalancer{fallback: &segkafka.Hash{}}
	message := segkafka.Message{
		Key:        []byte("__cowork_projection_snapshot_complete__:2"),
		WriterData: explicitPartition(2),
	}
	if got := balancer.Balance(message, 0, 1, 2); got != 2 {
		t.Fatalf("explicit marker partition = %d, want 2", got)
	}
	if got := balancer.Balance(message, 0, 1); got != 2 {
		t.Fatalf("stale explicit marker partition = %d, want broker rejection on 2", got)
	}
}

func TestBuildPresenceSnapshotRowsPreservesStoredTimeBeforePartitionMarkers(t *testing.T) {
	t.Parallel()

	stored := time.Date(2026, time.August, 26, 1, 2, 3, 456000000, time.UTC)
	markerTime := stored.Add(time.Hour)
	rows, err := buildPresenceSnapshotRows(
		"user.presence.event",
		[]domain.UserPresenceState{{
			UserID:     42,
			Status:     domain.PresenceOffline,
			OccurredAt: stored,
		}},
		[]int{0, 1},
		"00000000-0000-4000-8000-000000000001",
		markerTime,
	)
	if err != nil {
		t.Fatalf("buildPresenceSnapshotRows() error = %v", err)
	}
	if len(rows) != 3 {
		t.Fatalf("rows = %d, want one state plus two markers", len(rows))
	}
	if rows[0].key != "42" || rows[0].partition != nil {
		t.Fatalf("first row = %+v, want hash-partitioned state", rows[0])
	}

	var stateEvent domain.UserPresenceEvent
	if err := json.Unmarshal(rows[0].payload, &stateEvent); err != nil {
		t.Fatal(err)
	}
	if !stateEvent.OccurredAt.Equal(stored) {
		t.Fatalf("state occurredAt = %s, want stored %s", stateEvent.OccurredAt, stored)
	}
	if stateEvent.Status != domain.PresenceOffline {
		t.Fatalf("state status = %q, want offline tombstone", stateEvent.Status)
	}

	for index, partition := range []int{0, 1} {
		row := rows[index+1]
		if row.partition == nil || *row.partition != partition {
			t.Fatalf("marker %d partition = %v", index, row.partition)
		}
		if row.key != snapshotBarrierKeyPrefix+strconv.Itoa(partition) {
			t.Fatalf("marker %d key = %q", index, row.key)
		}
	}
}
