package kafka

import (
	"context"
	"errors"
	"testing"

	"github.com/cowork/cowork-notification/internal/domain/projection"
	segkafka "github.com/segmentio/kafka-go"
)

const testProjectionTopicID = "93b19168-4a63-49cd-b01d-b8d0667a1cb5"

type fakeProjectionProcessor struct {
	channelApplied int
	userApplied    int
	teamApplied    int
	markerRecorded int
	checkpoint     projection.Checkpoint
	marker         projection.SnapshotMarkerReceipt
	quarantined    *projection.InvalidRecord
	applyErr       error
	tried          chan struct{}
}

type noopProjectionReadiness struct{}

func (*noopProjectionReadiness) Set(bool) {}

func (p *fakeProjectionProcessor) RecordSnapshotMarkerWithCheckpoint(
	_ context.Context,
	checkpoint projection.Checkpoint,
	marker projection.SnapshotMarkerReceipt,
) error {
	p.markerRecorded++
	if err := p.recordAttempt(); err != nil {
		return err
	}
	p.checkpoint = checkpoint
	p.marker = marker
	return nil
}

func (p *fakeProjectionProcessor) recordAttempt() error {
	if p.tried != nil {
		select {
		case <-p.tried:
		default:
			close(p.tried)
		}
	}
	return p.applyErr
}

func (p *fakeProjectionProcessor) ApplyChannelNotificationWithCheckpoint(
	_ context.Context,
	_ projection.ChannelNotificationEvent,
	checkpoint projection.Checkpoint,
) error {
	p.channelApplied++
	if err := p.recordAttempt(); err != nil {
		return err
	}
	p.checkpoint = checkpoint
	return nil
}

func (p *fakeProjectionProcessor) ApplyUserProfileWithCheckpoint(
	_ context.Context,
	_ projection.UserProfileEvent,
	checkpoint projection.Checkpoint,
) error {
	p.userApplied++
	if err := p.recordAttempt(); err != nil {
		return err
	}
	p.checkpoint = checkpoint
	return nil
}

func (p *fakeProjectionProcessor) ApplyTeamLifecycleWithCheckpoint(
	_ context.Context,
	_ projection.TeamLifecycleEvent,
	checkpoint projection.Checkpoint,
) error {
	p.teamApplied++
	if err := p.recordAttempt(); err != nil {
		return err
	}
	p.checkpoint = checkpoint
	return nil
}

func (p *fakeProjectionProcessor) QuarantineWithCheckpoint(
	_ context.Context,
	record projection.InvalidRecord,
) error {
	p.quarantined = &record
	return nil
}

func (p *fakeProjectionProcessor) LoadCheckpoint(
	context.Context,
	string,
	string,
	int,
) (projection.CheckpointState, bool, error) {
	return projection.CheckpointState{}, false, nil
}

func (p *fakeProjectionProcessor) AdvanceCheckpoint(context.Context, projection.Checkpoint) error {
	return errors.New("not used")
}

func (p *fakeProjectionProcessor) LoadCheckpoints(
	context.Context,
	string,
	[]projection.TopicPartition,
) (map[projection.TopicPartition]projection.CheckpointState, error) {
	return nil, errors.New("not used")
}

func TestProjectionBarrierComplete_requiresAllThreeTopicCheckpoints(t *testing.T) {
	t.Parallel()
	preference := projection.TopicPartition{Topic: "preference.channel-notification.changed", Partition: 0}
	user := projection.TopicPartition{Topic: "user.profile.event", Partition: 0}
	team := projection.TopicPartition{Topic: "team.lifecycle", Partition: 0}
	ranges := map[projection.TopicPartition]projectionOffsetRange{
		preference: {First: 0, End: 4, TopicID: testProjectionTopicID},
		user:       {First: 0, End: 8, TopicID: testProjectionTopicID},
		team:       {First: 0, End: 1, TopicID: testProjectionTopicID},
	}
	preferenceMarker := int64(3)
	userMarker := int64(7)
	teamMarker := int64(0)

	if projectionBarrierComplete(ranges, map[projection.TopicPartition]projection.CheckpointState{
		preference: {TopicID: testProjectionTopicID, NextOffset: 4, SnapshotCompletedOffset: &preferenceMarker},
		user:       {TopicID: testProjectionTopicID, NextOffset: 8, SnapshotCompletedOffset: &userMarker},
	}) {
		t.Fatal("barrier completed without the empty team topic checkpoint")
	}
	if projectionBarrierComplete(ranges, map[projection.TopicPartition]projection.CheckpointState{
		preference: {TopicID: testProjectionTopicID, NextOffset: 4, SnapshotCompletedOffset: &preferenceMarker},
		user:       {TopicID: testProjectionTopicID, NextOffset: 8, SnapshotCompletedOffset: &userMarker},
		team:       {TopicID: testProjectionTopicID, NextOffset: 0},
	}) {
		t.Fatal("barrier completed without a snapshot marker for every startup partition")
	}
	if projectionBarrierComplete(ranges, map[projection.TopicPartition]projection.CheckpointState{
		preference: {TopicID: testProjectionTopicID, NextOffset: 4, SnapshotCompletedOffset: &preferenceMarker},
		user:       {TopicID: testProjectionTopicID, NextOffset: 7, SnapshotCompletedOffset: &userMarker},
		team:       {TopicID: testProjectionTopicID, NextOffset: 1, SnapshotCompletedOffset: &teamMarker},
	}) {
		t.Fatal("barrier completed before the user profile checkpoint reached its target")
	}
	if !projectionBarrierComplete(
		ranges,
		map[projection.TopicPartition]projection.CheckpointState{
			preference: {TopicID: testProjectionTopicID, NextOffset: 4, SnapshotCompletedOffset: &preferenceMarker},
			user:       {TopicID: testProjectionTopicID, NextOffset: 8, SnapshotCompletedOffset: &userMarker},
			team:       {TopicID: testProjectionTopicID, NextOffset: 1, SnapshotCompletedOffset: &teamMarker},
		},
	) {
		t.Fatal("barrier did not complete after every shared checkpoint reached its target and marker")
	}
}

func TestProjectionCheckpointResumeOffset_distinguishesMissingAndOutOfRangeCheckpoints(t *testing.T) {
	t.Parallel()
	offsetRange := projectionOffsetRange{First: 0, End: 12, TopicID: testProjectionTopicID}

	if _, err := projectionCheckpointResumeOffset(
		projection.CheckpointState{TopicID: testProjectionTopicID, NextOffset: 13},
		true,
		offsetRange,
	); !errors.Is(
		err,
		ErrProjectionCheckpointAheadOfTopic,
	) {
		t.Fatalf("projectionCheckpointResumeOffset() error = %v, want ahead-of-topic error", err)
	}
	if got, err := projectionCheckpointResumeOffset(
		projection.CheckpointState{TopicID: testProjectionTopicID, NextOffset: 12},
		true,
		offsetRange,
	); err != nil || got != 12 {
		t.Fatalf("checkpoint at end = (%d, %v), want (12, nil)", got, err)
	}
	if _, err := projectionCheckpointResumeOffset(
		projection.CheckpointState{TopicID: testProjectionTopicID, NextOffset: 6},
		true,
		projectionOffsetRange{First: 7, End: 12, TopicID: testProjectionTopicID},
	); !errors.Is(err, ErrProjectionCheckpointBehindTopic) {
		t.Fatalf("checkpoint behind topic error = %v, want behind-topic error", err)
	}
	if got, err := projectionCheckpointResumeOffset(
		projection.CheckpointState{},
		false,
		projectionOffsetRange{First: 7, End: 12, TopicID: testProjectionTopicID},
	); err != nil || got != 7 {
		t.Fatalf("missing checkpoint = (%d, %v), want earliest offset 7", got, err)
	}
}

func TestProjectionConsumer_validEventPassesNextOffsetToAtomicApply(t *testing.T) {
	t.Parallel()
	processor := &fakeProjectionProcessor{}
	consumer := &ProjectionConsumer{
		groupID: "cowork-notification-projections",
		topics: ProjectionTopics{
			ChannelNotification: "preference.channel-notification.changed",
			UserProfile:         "user.profile.event",
			TeamLifecycle:       "team.lifecycle",
		},
		processor: processor,
	}
	message := segkafka.Message{
		Topic:     "user.profile.event",
		Partition: 2,
		Offset:    17,
		Key:       []byte("42"),
		Value: []byte(
			"{\"eventType\":\"UPSERT\",\"userId\":42,\"nickname\":\"길동\",\"occurredAt\":\"2026-08-26T01:02:03Z\"}",
		),
	}

	if !consumer.processWithRetry(context.Background(), message, testProjectionTopicID) {
		t.Fatal("processWithRetry() = false")
	}
	if processor.userApplied != 1 {
		t.Fatalf("user apply count = %d, want 1", processor.userApplied)
	}
	if processor.checkpoint.NextOffset != 18 || processor.checkpoint.Partition != 2 ||
		processor.checkpoint.TopicID != testProjectionTopicID {
		t.Fatalf("checkpoint = %+v", processor.checkpoint)
	}
}

func TestProjectionConsumer_keyMismatchIsQuarantinedWithCheckpoint(t *testing.T) {
	t.Parallel()
	processor := &fakeProjectionProcessor{}
	readiness := &noopProjectionReadiness{}
	consumer := &ProjectionConsumer{
		groupID: "cowork-notification-projections",
		topics: ProjectionTopics{
			ChannelNotification: "preference.channel-notification.changed",
			UserProfile:         "user.profile.event",
			TeamLifecycle:       "team.lifecycle",
		},
		processor: processor,
		readiness: readiness,
	}
	message := segkafka.Message{
		Topic:     "team.lifecycle",
		Partition: 1,
		Offset:    9,
		Key:       []byte("wrong"),
		Value: []byte(
			"{\"eventType\":\"MEMBER_JOINED\",\"teamId\":7,\"teamName\":\"팀\",\"occurredAt\":\"2026-08-26T01:02:03Z\"}",
		),
	}

	if !consumer.processWithRetry(context.Background(), message, testProjectionTopicID) {
		t.Fatal("processWithRetry() = false")
	}
	if processor.teamApplied != 0 {
		t.Fatalf("team apply count = %d, want 0", processor.teamApplied)
	}
	if processor.quarantined == nil || processor.quarantined.Checkpoint.NextOffset != 10 {
		t.Fatalf("quarantined record = %+v", processor.quarantined)
	}
}

func TestProjectionConsumer_snapshotMarkerSkipsDomainAndRecordsReceiptAtomically(t *testing.T) {
	t.Parallel()
	processor := &fakeProjectionProcessor{}
	consumer := &ProjectionConsumer{
		groupID: "cowork-notification-projections",
		topics: ProjectionTopics{
			ChannelNotification: "preference.channel-notification.changed",
			UserProfile:         "user.profile.event",
			TeamLifecycle:       "team.lifecycle",
		},
		processor: processor,
	}
	message := segkafka.Message{
		Topic:     "user.profile.event",
		Partition: 2,
		Offset:    17,
		Key:       []byte("__cowork_projection_snapshot_complete__:2"),
		Value: []byte(
			`{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"user.profile.event","partition":2,"snapshotId":"93b19168-4a63-49cd-b01d-b8d0667a1cb5","occurredAt":"2026-08-26T01:02:03Z","source":"cowork-user"}`,
		),
	}

	if !consumer.processWithRetry(context.Background(), message, testProjectionTopicID) {
		t.Fatal("processWithRetry() = false")
	}
	if processor.markerRecorded != 1 || processor.channelApplied != 0 || processor.userApplied != 0 || processor.teamApplied != 0 {
		t.Fatalf(
			"marker/domain counts = %d/%d/%d/%d",
			processor.markerRecorded,
			processor.channelApplied,
			processor.userApplied,
			processor.teamApplied,
		)
	}
	if processor.marker.Offset != 17 || processor.marker.Source != "cowork-user" {
		t.Fatalf("marker receipt = %+v", processor.marker)
	}
	if processor.checkpoint.NextOffset != 18 {
		t.Fatalf("checkpoint = %+v", processor.checkpoint)
	}
}

func TestProjectionConsumer_wrongSourceSnapshotMarkerIsQuarantinedBeforeDomain(t *testing.T) {
	t.Parallel()
	processor := &fakeProjectionProcessor{}
	readiness := &noopProjectionReadiness{}
	consumer := &ProjectionConsumer{
		groupID: "cowork-notification-projections",
		topics: ProjectionTopics{
			ChannelNotification: "preference.channel-notification.changed",
			UserProfile:         "user.profile.event",
			TeamLifecycle:       "team.lifecycle",
		},
		processor: processor,
		readiness: readiness,
	}
	message := segkafka.Message{
		Topic:     "user.profile.event",
		Partition: 2,
		Offset:    17,
		Key:       []byte("__cowork_projection_snapshot_complete__:2"),
		Value: []byte(
			`{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"user.profile.event","partition":2,"snapshotId":"93b19168-4a63-49cd-b01d-b8d0667a1cb5","occurredAt":"2026-08-26T01:02:03Z","source":"cowork-deprecated"}`,
		),
	}

	if !consumer.processWithRetry(context.Background(), message, testProjectionTopicID) {
		t.Fatal("processWithRetry() = false")
	}
	if processor.quarantined == nil || processor.quarantined.Checkpoint.NextOffset != 18 {
		t.Fatalf("quarantined marker = %+v", processor.quarantined)
	}
	if processor.markerRecorded != 0 || processor.userApplied != 0 {
		t.Fatalf("invalid marker reached a handler: marker=%d user=%d", processor.markerRecorded, processor.userApplied)
	}
}

func TestParseSnapshotMarker_requiresExplicitPartitionForPartitionZero(t *testing.T) {
	t.Parallel()
	message := segkafka.Message{
		Topic:     "user.profile.event",
		Partition: 0,
		Offset:    1,
		Key:       []byte("__cowork_projection_snapshot_complete__:0"),
		Value: []byte(
			`{"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"user.profile.event","snapshotId":"93b19168-4a63-49cd-b01d-b8d0667a1cb5","occurredAt":"2026-08-26T01:02:03Z","source":"cowork-user"}`,
		),
	}

	isMarker, _, err := parseSnapshotMarker(message, userProfileSnapshotSource)
	if !isMarker || !errors.Is(err, projection.ErrInvalidEvent) {
		t.Fatalf("parseSnapshotMarker() = (%v, %v), want marker contract error", isMarker, err)
	}
}

func TestProjectionConsumer_transientDatabaseFailureDoesNotAdvanceOrQuarantine(t *testing.T) {
	t.Parallel()
	tried := make(chan struct{})
	processor := &fakeProjectionProcessor{
		applyErr: errors.New("mysql temporarily unavailable"),
		tried:    tried,
	}
	consumer := &ProjectionConsumer{
		groupID: "cowork-notification-projections",
		topics: ProjectionTopics{
			ChannelNotification: "preference.channel-notification.changed",
			UserProfile:         "user.profile.event",
			TeamLifecycle:       "team.lifecycle",
		},
		processor: processor,
	}
	message := segkafka.Message{
		Topic:     "user.profile.event",
		Partition: 0,
		Offset:    3,
		Key:       []byte("42"),
		Value: []byte(
			"{\"eventType\":\"UPSERT\",\"userId\":42,\"nickname\":\"길동\",\"occurredAt\":\"2026-08-26T01:02:03Z\"}",
		),
	}
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan bool, 1)
	go func() { result <- consumer.processWithRetry(ctx, message, testProjectionTopicID) }()
	<-tried
	cancel()

	if <-result {
		t.Fatal("processWithRetry() = true after transient failure and cancellation")
	}
	if processor.quarantined != nil {
		t.Fatalf("transient record was quarantined: %+v", processor.quarantined)
	}
	if processor.checkpoint.NextOffset != 0 {
		t.Fatalf("checkpoint advanced after transient failure: %+v", processor.checkpoint)
	}
}
