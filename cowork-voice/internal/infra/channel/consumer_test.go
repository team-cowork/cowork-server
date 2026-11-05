package channel

import (
	"context"
	"errors"
	"sync"
	"testing"
)

var currentTestPartition = TopicPartition{Topic: "channel.state.v1", Partition: 0}

func currentTestRanges() map[TopicPartition]OffsetRange {
	return map[TopicPartition]OffsetRange{
		currentTestPartition: {First: 0, End: 5, TopicID: "topic-id"},
	}
}

type blockingSnapshotter struct {
	mu      sync.Mutex
	calls   int
	entered chan struct{}
	release chan struct{}
}

func newBlockingSnapshotter() *blockingSnapshotter {
	return &blockingSnapshotter{entered: make(chan struct{}), release: make(chan struct{})}
}

// Snapshot blocks only the first call until release is closed.
func (s *blockingSnapshotter) Snapshot(context.Context, []string) (map[TopicPartition]OffsetRange, error) {
	s.mu.Lock()
	s.calls++
	first := s.calls == 1
	s.mu.Unlock()
	if first {
		close(s.entered)
		<-s.release
	}
	return currentTestRanges(), nil
}

type currentCheckpointStore struct {
	CheckpointStore
}

func (currentCheckpointStore) LoadAll(
	context.Context,
	string,
	[]TopicPartition,
) (map[TopicPartition]CheckpointState, error) {
	marker := int64(2)
	return map[TopicPartition]CheckpointState{
		currentTestPartition: {TopicID: "topic-id", NextOffset: 5, SnapshotCompletedOffset: &marker},
	}, nil
}

type fakeReadiness struct {
	mu    sync.Mutex
	ready bool
}

func (r *fakeReadiness) Set(ready bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.ready = ready
}

func (r *fakeReadiness) IsReady() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.ready
}

func newCurrentTestConsumer(snapshotter offsetSnapshotter, readiness *fakeReadiness) *Consumer {
	return &Consumer{
		topics:           []string{currentTestPartition.Topic},
		groupID:          "cowork-voice",
		checkpoints:      currentCheckpointStore{},
		snapshotter:      snapshotter,
		readiness:        readiness,
		generationToken:  1,
		generationRanges: currentTestRanges(),
	}
}

// startBlockedEstablishCurrent runs EstablishCurrent until it is blocked inside
// the broker snapshot, so a later check can overlap it.
func startBlockedEstablishCurrent(consumer *Consumer, snapshotter *blockingSnapshotter) <-chan error {
	result := make(chan error, 1)
	go func() {
		result <- consumer.EstablishCurrent(context.Background())
	}()
	<-snapshotter.entered
	return result
}

func TestEstablishCurrentOverlappingChecksBothSucceed(t *testing.T) {
	snapshotter := newBlockingSnapshotter()
	readiness := &fakeReadiness{}
	consumer := newCurrentTestConsumer(snapshotter, readiness)

	first := startBlockedEstablishCurrent(consumer, snapshotter)
	if err := consumer.EstablishCurrent(context.Background()); err != nil {
		t.Fatalf("second check: got %v, want nil", err)
	}
	close(snapshotter.release)

	if err := <-first; err != nil {
		t.Fatalf("superseded check: got %v, want its own nil result", err)
	}
	if !readiness.IsReady() {
		t.Fatal("readiness: got false, want true")
	}
}

func TestEstablishCurrentSupersededCheckDoesNotPublishReadiness(t *testing.T) {
	snapshotter := newBlockingSnapshotter()
	readiness := &fakeReadiness{ready: true}
	consumer := newCurrentTestConsumer(snapshotter, readiness)

	first := startBlockedEstablishCurrent(consumer, snapshotter)
	consumer.closeReadinessForStateGap()
	close(snapshotter.release)

	if err := <-first; err != nil {
		t.Fatalf("superseded check: got %v, want its own nil result", err)
	}
	if readiness.IsReady() {
		t.Fatal("readiness: got true, want the state gap to keep it closed")
	}
}

func TestEstablishCurrentSupersededCheckFailsWhenGenerationChanged(t *testing.T) {
	snapshotter := newBlockingSnapshotter()
	readiness := &fakeReadiness{}
	consumer := newCurrentTestConsumer(snapshotter, readiness)

	first := startBlockedEstablishCurrent(consumer, snapshotter)
	consumer.closeReadinessForStateGap()
	consumer.generationMu.Lock()
	consumer.generationToken++
	consumer.generationMu.Unlock()
	close(snapshotter.release)

	if err := <-first; !errors.Is(err, ErrProjectionNotCurrent) {
		t.Fatalf("superseded check after generation change: got %v, want ErrProjectionNotCurrent", err)
	}
}
