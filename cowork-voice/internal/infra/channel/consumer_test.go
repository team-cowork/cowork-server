package channel

import (
	"context"
	"errors"
	"reflect"
	"testing"

	segkafka "github.com/segmentio/kafka-go"
)

type checkpointCallStore struct {
	calls       *[]string
	checkpoints map[TopicPartition]CheckpointState
	deadLetters []DeadLetter
	markers     []SnapshotMarkerReceipt
}

func (s *checkpointCallStore) Load(
	_ context.Context,
	_ string,
	topic string,
	partition int,
) (CheckpointState, bool, error) {
	checkpoint, found := s.checkpoints[TopicPartition{Topic: topic, Partition: partition}]
	return checkpoint, found, nil
}

func (s *checkpointCallStore) Advance(
	_ context.Context,
	_, topic string,
	partition int,
	nextOffset int64,
) error {
	*s.calls = append(*s.calls, "checkpoint")
	checkpoint := s.checkpoints[TopicPartition{Topic: topic, Partition: partition}]
	checkpoint.NextOffset = nextOffset
	s.checkpoints[TopicPartition{Topic: topic, Partition: partition}] = checkpoint
	return nil
}

func (s *checkpointCallStore) RecordSnapshotMarker(
	_ context.Context,
	_, topic string,
	partition int,
	nextOffset int64,
	marker SnapshotMarkerReceipt,
) error {
	*s.calls = append(*s.calls, "marker_checkpoint")
	markerOffset := marker.Offset
	s.checkpoints[TopicPartition{Topic: topic, Partition: partition}] = CheckpointState{
		NextOffset:              nextOffset,
		SnapshotCompletedOffset: &markerOffset,
	}
	s.markers = append(s.markers, marker)
	return nil
}

func (s *checkpointCallStore) LoadAll(
	_ context.Context,
	_ string,
	_ []TopicPartition,
) (map[TopicPartition]CheckpointState, error) {
	return s.checkpoints, nil
}

func (s *checkpointCallStore) Quarantine(_ context.Context, deadLetter DeadLetter) error {
	*s.calls = append(*s.calls, "quarantine")
	s.deadLetters = append(s.deadLetters, deadLetter)
	return nil
}

type orderedMembershipStore struct {
	calls *[]string
	err   error
	tried chan struct{}
}

func (s *orderedMembershipStore) Upsert(_ context.Context, _ Membership) error {
	*s.calls = append(*s.calls, "apply")
	if s.tried != nil {
		select {
		case <-s.tried:
		default:
			close(s.tried)
		}
	}
	return s.err
}

func (s *orderedMembershipStore) Deactivate(_ context.Context, _ Membership) error {
	*s.calls = append(*s.calls, "apply")
	if s.tried != nil {
		select {
		case <-s.tried:
		default:
			close(s.tried)
		}
	}
	return s.err
}

func TestBarrierComplete_requiresEverySharedCheckpointAtFixedEndOffset(t *testing.T) {
	t.Parallel()
	one := TopicPartition{Topic: "channel.member.event", Partition: 0}
	two := TopicPartition{Topic: "channel.member.event", Partition: 1}
	ranges := map[TopicPartition]OffsetRange{
		one: {First: 0, End: 11},
		two: {First: 0, End: 0},
	}
	oneMarker := int64(10)
	twoMarker := int64(0)

	if barrierComplete(ranges, map[TopicPartition]CheckpointState{
		one: {NextOffset: 11, SnapshotCompletedOffset: &oneMarker},
	}) {
		t.Fatal("barrier completed without the empty partition checkpoint")
	}
	if barrierComplete(ranges, map[TopicPartition]CheckpointState{
		one: {NextOffset: 11, SnapshotCompletedOffset: &oneMarker},
		two: {NextOffset: 0},
	}) {
		t.Fatal("barrier completed without a snapshot marker for every startup partition")
	}
	if barrierComplete(ranges, map[TopicPartition]CheckpointState{
		one: {NextOffset: 10, SnapshotCompletedOffset: &oneMarker},
		two: {NextOffset: 1, SnapshotCompletedOffset: &twoMarker},
	}) {
		t.Fatal("barrier completed before partition 0 reached its fixed end offset")
	}
	staleMarker := int64(4)
	retainedRange := map[TopicPartition]OffsetRange{one: {First: 5, End: 11}}
	if barrierComplete(retainedRange, map[TopicPartition]CheckpointState{
		one: {NextOffset: 11, SnapshotCompletedOffset: &staleMarker},
	}) {
		t.Fatal("barrier completed with a marker outside the retained topic range")
	}
	if !barrierComplete(ranges, map[TopicPartition]CheckpointState{
		one: {NextOffset: 11, SnapshotCompletedOffset: &oneMarker},
		two: {NextOffset: 1, SnapshotCompletedOffset: &twoMarker},
	}) {
		t.Fatal("barrier did not complete after every checkpoint reached its target and marker")
	}
}

func TestCheckpointResumeOffset_distinguishesMissingAndOutOfRangeCheckpoints(t *testing.T) {
	t.Parallel()
	offsetRange := OffsetRange{First: 0, End: 12}

	if _, err := checkpointResumeOffset(13, true, offsetRange); !errors.Is(err, ErrCheckpointAheadOfTopic) {
		t.Fatalf("checkpointResumeOffset() error = %v, want ErrCheckpointAheadOfTopic", err)
	}
	if got, err := checkpointResumeOffset(12, true, offsetRange); err != nil || got != 12 {
		t.Fatalf("checkpoint at end = (%d, %v), want (12, nil)", got, err)
	}
	if _, err := checkpointResumeOffset(6, true, OffsetRange{First: 7, End: 12}); !errors.Is(
		err,
		ErrCheckpointBehindTopic,
	) {
		t.Fatalf("checkpoint behind topic error = %v, want ErrCheckpointBehindTopic", err)
	}
	if got, err := checkpointResumeOffset(0, false, OffsetRange{First: 7, End: 12}); err != nil || got != 7 {
		t.Fatalf("missing checkpoint = (%d, %v), want earliest offset 7", got, err)
	}
}

func TestProcessWithRetry_advancesCheckpointOnlyAfterProjectionApply(t *testing.T) {
	t.Parallel()
	calls := []string{}
	checkpoints := &checkpointCallStore{
		calls:       &calls,
		checkpoints: map[TopicPartition]CheckpointState{},
	}
	consumer := &Consumer{
		groupID:     "cowork-voice.channel-member",
		handler:     NewEventHandler(&orderedMembershipStore{calls: &calls}),
		checkpoints: checkpoints,
	}
	message := segkafka.Message{
		Topic:     "channel.member.event",
		Partition: 2,
		Offset:    41,
		Key:       []byte("7:9"),
		Value: []byte(
			`{"eventType":"JOIN","channelId":7,"teamId":3,"userId":9,"occurredAt":"2026-08-26T01:02:03Z"}`,
		),
	}

	if !consumer.processWithRetry(context.Background(), message) {
		t.Fatal("processWithRetry() = false")
	}
	if !reflect.DeepEqual(calls, []string{"apply", "checkpoint"}) {
		t.Fatalf("call order = %v", calls)
	}
	if got := checkpoints.checkpoints[TopicPartition{Topic: message.Topic, Partition: message.Partition}].NextOffset; got != 42 {
		t.Fatalf("next offset = %d, want 42", got)
	}
}

func TestProcessWithRetry_quarantinesInvalidRecordBeforeAdvancingCheckpoint(t *testing.T) {
	t.Parallel()
	calls := []string{}
	checkpoints := &checkpointCallStore{
		calls:       &calls,
		checkpoints: map[TopicPartition]CheckpointState{},
	}
	consumer := &Consumer{
		groupID:     "cowork-voice.channel-member",
		handler:     NewEventHandler(&orderedMembershipStore{calls: &calls}),
		checkpoints: checkpoints,
	}
	message := segkafka.Message{
		Topic:     "channel.member.event",
		Partition: 1,
		Offset:    8,
		Key:       []byte("wrong-key"),
		Value: []byte(
			`{"eventType":"JOIN","channelId":7,"teamId":3,"userId":9,"occurredAt":"2026-08-26T01:02:03Z"}`,
		),
	}

	if !consumer.processWithRetry(context.Background(), message) {
		t.Fatal("processWithRetry() = false")
	}
	if !reflect.DeepEqual(calls, []string{"quarantine", "checkpoint"}) {
		t.Fatalf("call order = %v", calls)
	}
	if len(checkpoints.deadLetters) != 1 {
		t.Fatalf("dead letters = %d, want 1", len(checkpoints.deadLetters))
	}
	if !errors.Is(consumer.handler.Handle(context.Background(), "wrong", message.Value), ErrInvalidEvent) {
		t.Fatal("fixture is not an invalid event")
	}
}

func TestProcessWithRetry_snapshotMarkerSkipsDomainAndRecordsCheckpointAtomically(t *testing.T) {
	t.Parallel()
	calls := []string{}
	checkpoints := &checkpointCallStore{
		calls:       &calls,
		checkpoints: map[TopicPartition]CheckpointState{},
	}
	consumer := &Consumer{
		groupID:     "cowork-voice.channel-member",
		handler:     NewEventHandler(&orderedMembershipStore{calls: &calls}),
		checkpoints: checkpoints,
	}
	message := segkafka.Message{
		Topic:     "channel.member.event",
		Partition: 2,
		Offset:    41,
		Key:       []byte("__cowork_projection_snapshot_complete__:2"),
		Value: []byte(
			`{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"channel.member.event","partition":2,"snapshotId":"93b19168-4a63-49cd-b01d-b8d0667a1cb5","occurredAt":"2026-08-26T01:02:03Z","source":"cowork-channel"}`,
		),
	}

	if !consumer.processWithRetry(context.Background(), message) {
		t.Fatal("processWithRetry() = false")
	}
	if !reflect.DeepEqual(calls, []string{"marker_checkpoint"}) {
		t.Fatalf("call order = %v", calls)
	}
	if len(checkpoints.markers) != 1 || checkpoints.markers[0].Offset != 41 {
		t.Fatalf("marker receipts = %+v", checkpoints.markers)
	}
	checkpoint := checkpoints.checkpoints[TopicPartition{Topic: message.Topic, Partition: message.Partition}]
	if checkpoint.NextOffset != 42 || checkpoint.SnapshotCompletedOffset == nil || *checkpoint.SnapshotCompletedOffset != 41 {
		t.Fatalf("checkpoint = %+v", checkpoint)
	}
}

func TestProcessWithRetry_wrongSourceSnapshotMarkerIsQuarantinedBeforeDomain(t *testing.T) {
	t.Parallel()
	calls := []string{}
	checkpoints := &checkpointCallStore{
		calls:       &calls,
		checkpoints: map[TopicPartition]CheckpointState{},
	}
	consumer := &Consumer{
		groupID:     "cowork-voice.channel-member",
		handler:     NewEventHandler(&orderedMembershipStore{calls: &calls}),
		checkpoints: checkpoints,
	}
	message := segkafka.Message{
		Topic:     "channel.member.event",
		Partition: 2,
		Offset:    41,
		Key:       []byte("__cowork_projection_snapshot_complete__:2"),
		Value: []byte(
			`{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"channel.member.event","partition":2,"snapshotId":"93b19168-4a63-49cd-b01d-b8d0667a1cb5","occurredAt":"2026-08-26T01:02:03Z","source":"cowork-deprecated"}`,
		),
	}

	if !consumer.processWithRetry(context.Background(), message) {
		t.Fatal("processWithRetry() = false")
	}
	if !reflect.DeepEqual(calls, []string{"quarantine", "checkpoint"}) {
		t.Fatalf("call order = %v", calls)
	}
	if len(checkpoints.deadLetters) != 1 || len(checkpoints.markers) != 0 {
		t.Fatalf("dead letters/markers = %d/%d", len(checkpoints.deadLetters), len(checkpoints.markers))
	}
}

func TestParseSnapshotMarker_requiresExplicitPartitionForPartitionZero(t *testing.T) {
	t.Parallel()
	message := segkafka.Message{
		Topic:     "channel.member.event",
		Partition: 0,
		Offset:    1,
		Key:       []byte("__cowork_projection_snapshot_complete__:0"),
		Value: []byte(
			`{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"channel.member.event","snapshotId":"93b19168-4a63-49cd-b01d-b8d0667a1cb5","occurredAt":"2026-08-26T01:02:03Z","source":"cowork-channel"}`,
		),
	}

	isMarker, _, err := parseSnapshotMarker(message)
	if !isMarker || !errors.Is(err, ErrInvalidEvent) {
		t.Fatalf("parseSnapshotMarker() = (%v, %v), want marker contract error", isMarker, err)
	}
}

func TestProcessWithRetry_transientProjectionFailureDoesNotAdvanceCheckpoint(t *testing.T) {
	t.Parallel()
	calls := []string{}
	checkpoints := &checkpointCallStore{
		calls:       &calls,
		checkpoints: map[TopicPartition]CheckpointState{},
	}
	tried := make(chan struct{})
	consumer := &Consumer{
		groupID: "cowork-voice.channel-member",
		handler: NewEventHandler(&orderedMembershipStore{
			calls: &calls,
			err:   errors.New("mongodb temporarily unavailable"),
			tried: tried,
		}),
		checkpoints: checkpoints,
	}
	message := segkafka.Message{
		Topic:     "channel.member.event",
		Partition: 0,
		Offset:    5,
		Key:       []byte("7:9"),
		Value: []byte(
			`{"eventType":"JOIN","channelId":7,"teamId":3,"userId":9,"occurredAt":"2026-08-26T01:02:03Z"}`,
		),
	}
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan bool, 1)
	go func() { result <- consumer.processWithRetry(ctx, message) }()
	<-tried
	cancel()

	if <-result {
		t.Fatal("processWithRetry() = true after transient failure and cancellation")
	}
	if len(calls) != 1 || calls[0] != "apply" {
		t.Fatalf("calls = %v, checkpoint must not advance", calls)
	}
}
