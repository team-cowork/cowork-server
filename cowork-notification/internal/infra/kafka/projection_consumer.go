package kafka

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/cowork/cowork-notification/internal/domain/projection"
	segkafka "github.com/segmentio/kafka-go"
)

const (
	projectionRetryDelay              = time.Second
	barrierPollInterval               = 250 * time.Millisecond
	projectionMaxBytes                = 10e6
	snapshotMarkerKeyPrefix           = "__cowork_projection_snapshot_complete__:"
	snapshotMarkerEventType           = "PROJECTION_SNAPSHOT_COMPLETED"
	channelNotificationSnapshotSource = "cowork-preference"
	userProfileSnapshotSource         = "cowork-user"
	teamLifecycleSnapshotSource       = "cowork-team"
)

var snapshotIDPattern = regexp.MustCompile(
	`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$`,
)

var ErrProjectionCheckpointAheadOfTopic = errors.New("projection checkpoint is ahead of the topic end")
var ErrProjectionCheckpointBehindTopic = errors.New("projection checkpoint is behind the topic start")

type ProjectionTopics struct {
	ChannelNotification string
	UserProfile         string
	TeamLifecycle       string
}

func (t ProjectionTopics) all() []string {
	return []string{t.ChannelNotification, t.UserProfile, t.TeamLifecycle}
}

func (t ProjectionTopics) expectedSnapshotSource(topic string) (string, bool) {
	switch topic {
	case t.ChannelNotification:
		return channelNotificationSnapshotSource, true
	case t.UserProfile:
		return userProfileSnapshotSource, true
	case t.TeamLifecycle:
		return teamLifecycleSnapshotSource, true
	default:
		return "", false
	}
}

type projectionReadiness interface {
	Set(bool)
}

type projectionProcessor interface {
	ApplyChannelNotificationWithCheckpoint(
		context.Context,
		projection.ChannelNotificationEvent,
		projection.Checkpoint,
	) error
	ApplyUserProfileWithCheckpoint(
		context.Context,
		projection.UserProfileEvent,
		projection.Checkpoint,
	) error
	ApplyTeamLifecycleWithCheckpoint(
		context.Context,
		projection.TeamLifecycleEvent,
		projection.Checkpoint,
	) error
	RecordSnapshotMarkerWithCheckpoint(
		context.Context,
		projection.Checkpoint,
		projection.SnapshotMarkerReceipt,
	) error
	QuarantineWithCheckpoint(context.Context, projection.InvalidRecord) error
	LoadCheckpoint(context.Context, string, string, int) (int64, bool, error)
	AdvanceCheckpoint(context.Context, projection.Checkpoint) error
	LoadCheckpoints(
		context.Context,
		string,
		[]projection.TopicPartition,
	) (map[projection.TopicPartition]projection.CheckpointState, error)
}

type projectionOffsetRange struct {
	First int64
	End   int64
}

type projectionOffsetSnapshotter interface {
	Snapshot(
		ctx context.Context,
		topics []string,
	) (map[projection.TopicPartition]projectionOffsetRange, error)
}

type kafkaProjectionOffsetSnapshotter struct {
	client *segkafka.Client
}

func newKafkaProjectionOffsetSnapshotter(brokers []string) *kafkaProjectionOffsetSnapshotter {
	return &kafkaProjectionOffsetSnapshotter{client: &segkafka.Client{
		Addr:    segkafka.TCP(brokers...),
		Timeout: 10 * time.Second,
	}}
}

func (s *kafkaProjectionOffsetSnapshotter) Snapshot(
	ctx context.Context,
	topics []string,
) (map[projection.TopicPartition]projectionOffsetRange, error) {
	metadata, err := s.client.Metadata(ctx, &segkafka.MetadataRequest{Topics: topics})
	if err != nil {
		return nil, fmt.Errorf("load projection topic metadata: %w", err)
	}

	requestedTopics := make(map[string]struct{}, len(topics))
	for _, topic := range topics {
		requestedTopics[topic] = struct{}{}
	}
	offsetRequests := make(map[string][]segkafka.OffsetRequest, len(topics))
	expected := make(map[projection.TopicPartition]struct{})
	for _, topic := range metadata.Topics {
		if _, requested := requestedTopics[topic.Name]; !requested {
			continue
		}
		if topic.Error != nil {
			return nil, fmt.Errorf("load metadata for topic %s: %w", topic.Name, topic.Error)
		}
		delete(requestedTopics, topic.Name)
		for _, partition := range topic.Partitions {
			if partition.Error != nil {
				return nil, fmt.Errorf(
					"load metadata for %s/%d: %w",
					topic.Name,
					partition.ID,
					partition.Error,
				)
			}
			offsetRequests[topic.Name] = append(
				offsetRequests[topic.Name],
				segkafka.FirstOffsetOf(partition.ID),
				segkafka.LastOffsetOf(partition.ID),
			)
			expected[projection.TopicPartition{Topic: topic.Name, Partition: partition.ID}] = struct{}{}
		}
	}
	if len(requestedTopics) > 0 {
		return nil, fmt.Errorf("projection topic metadata missing: %v", mapKeys(requestedTopics))
	}
	if len(expected) == 0 {
		return nil, errors.New("projection topics have no partitions")
	}

	offsets, err := s.client.ListOffsets(ctx, &segkafka.ListOffsetsRequest{Topics: offsetRequests})
	if err != nil {
		return nil, fmt.Errorf("load projection topic offsets: %w", err)
	}
	ranges := make(map[projection.TopicPartition]projectionOffsetRange, len(expected))
	for topic, partitions := range offsets.Topics {
		for _, partition := range partitions {
			if partition.Error != nil {
				return nil, fmt.Errorf(
					"load offsets for %s/%d: %w",
					topic,
					partition.Partition,
					partition.Error,
				)
			}
			tp := projection.TopicPartition{Topic: topic, Partition: partition.Partition}
			ranges[tp] = projectionOffsetRange{
				First: partition.FirstOffset,
				End:   partition.LastOffset,
			}
		}
	}
	for partition := range expected {
		if _, ok := ranges[partition]; !ok {
			return nil, fmt.Errorf("offset response missing %s/%d", partition.Topic, partition.Partition)
		}
	}
	return ranges, nil
}

type ProjectionConsumer struct {
	brokers     []string
	groupID     string
	topics      ProjectionTopics
	group       *segkafka.ConsumerGroup
	processor   projectionProcessor
	snapshotter projectionOffsetSnapshotter
	readiness   projectionReadiness
	closeOnce   sync.Once
}

func NewProjectionConsumer(
	brokers string,
	groupID string,
	topics ProjectionTopics,
	processor projectionProcessor,
	readiness projectionReadiness,
) (*ProjectionConsumer, error) {
	brokerList := splitBrokerList(brokers)
	group, err := segkafka.NewConsumerGroup(segkafka.ConsumerGroupConfig{
		ID:                    groupID,
		Brokers:               brokerList,
		Topics:                topics.all(),
		StartOffset:           segkafka.FirstOffset,
		WatchPartitionChanges: true,
	})
	if err != nil {
		return nil, fmt.Errorf("create notification projection consumer group: %w", err)
	}
	return &ProjectionConsumer{
		brokers:     brokerList,
		groupID:     groupID,
		topics:      topics,
		group:       group,
		processor:   processor,
		snapshotter: newKafkaProjectionOffsetSnapshotter(brokerList),
		readiness:   readiness,
	}, nil
}

func (c *ProjectionConsumer) Start(ctx context.Context) {
	defer c.readiness.Set(false)
	for {
		generation, err := c.group.Next(ctx)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, segkafka.ErrGroupClosed) {
				return
			}
			slog.Error("projection consumer group failed", "err", err)
			if !waitForRetry(ctx) {
				return
			}
			continue
		}

		c.readiness.Set(false)
		generation.Start(func(generationCtx context.Context) {
			c.consumeGeneration(generationCtx, generation.Assignments)
		})
	}
}

func (c *ProjectionConsumer) consumeGeneration(
	ctx context.Context,
	assignments map[string][]segkafka.PartitionAssignment,
) {
	ranges, ok := c.snapshotWithRetry(ctx)
	if !ok || !c.initializeCheckpoints(ctx, ranges) {
		return
	}

	var readers sync.WaitGroup
	for topic, topicAssignments := range assignments {
		for _, assignment := range topicAssignments {
			partition := projection.TopicPartition{Topic: topic, Partition: assignment.ID}
			offsetRange, exists := ranges[partition]
			if !exists {
				slog.Error("assigned projection partition missing from barrier", "topic", topic, "partition", assignment.ID)
				continue
			}
			readers.Add(1)
			go func() {
				defer readers.Done()
				c.consumePartition(ctx, partition, offsetRange)
			}()
		}
	}

	c.monitorBarrier(ctx, ranges)
	readers.Wait()
	c.readiness.Set(false)
}

func (c *ProjectionConsumer) snapshotWithRetry(
	ctx context.Context,
) (map[projection.TopicPartition]projectionOffsetRange, bool) {
	for {
		ranges, err := c.snapshotter.Snapshot(ctx, c.topics.all())
		if err == nil {
			return ranges, true
		}
		if ctx.Err() != nil {
			return nil, false
		}
		slog.Error("projection high-watermark capture failed; retrying", "err", err)
		if !waitForRetry(ctx) {
			return nil, false
		}
	}
}

func (c *ProjectionConsumer) initializeCheckpoints(
	ctx context.Context,
	ranges map[projection.TopicPartition]projectionOffsetRange,
) bool {
	for partition, offsetRange := range ranges {
		for {
			nextOffset, found, err := c.processor.LoadCheckpoint(
				ctx,
				c.groupID,
				partition.Topic,
				partition.Partition,
			)
			if err == nil {
				resumeOffset, policyErr := projectionCheckpointResumeOffset(nextOffset, found, offsetRange)
				if policyErr != nil {
					err = policyErr
				} else if found && resumeOffset == nextOffset {
					break
				} else {
					err = c.processor.AdvanceCheckpoint(ctx, projection.Checkpoint{
						ConsumerGroup: c.groupID,
						Topic:         partition.Topic,
						Partition:     partition.Partition,
						NextOffset:    resumeOffset,
					})
				}
			}
			if err == nil {
				break
			}
			if ctx.Err() != nil {
				return false
			}
			if errors.Is(err, ErrProjectionCheckpointAheadOfTopic) {
				slog.Error(
					"projection checkpoint is ahead of Kafka; refusing readiness and seek",
					"topic", partition.Topic,
					"partition", partition.Partition,
					"checkpoint", nextOffset,
					"topicEnd", offsetRange.End,
					"action", "verify topic recreation and reset the shared projection checkpoint",
				)
			} else if errors.Is(err, ErrProjectionCheckpointBehindTopic) {
				slog.Error(
					"projection checkpoint is behind Kafka retention; refusing readiness and seek",
					"topic", partition.Topic,
					"partition", partition.Partition,
					"checkpoint", nextOffset,
					"topicStart", offsetRange.First,
					"action", "rebuild the projection and reset its shared checkpoint together",
				)
			} else {
				slog.Error(
					"projection checkpoint initialization failed; retrying",
					"topic", partition.Topic,
					"partition", partition.Partition,
					"err", err,
				)
			}
			if !waitForRetry(ctx) {
				return false
			}
		}
	}
	return true
}

func (c *ProjectionConsumer) consumePartition(
	ctx context.Context,
	partition projection.TopicPartition,
	offsetRange projectionOffsetRange,
) {
	nextOffset, ok := c.loadCheckpointWithRetry(ctx, partition, offsetRange)
	if !ok {
		return
	}
	reader := segkafka.NewReader(segkafka.ReaderConfig{
		Brokers:   c.brokers,
		Topic:     partition.Topic,
		Partition: partition.Partition,
		MinBytes:  1,
		MaxBytes:  projectionMaxBytes,
	})
	defer func() { _ = reader.Close() }()
	if err := reader.SetOffset(nextOffset); err != nil {
		slog.Error(
			"projection checkpoint seek failed",
			"topic", partition.Topic,
			"partition", partition.Partition,
			"offset", nextOffset,
			"err", err,
		)
		return
	}

	for {
		message, err := reader.ReadMessage(ctx)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, segkafka.ErrGenerationEnded) {
				return
			}
			slog.Error(
				"projection event fetch failed; retrying",
				"topic", partition.Topic,
				"partition", partition.Partition,
				"err", err,
			)
			if !waitForRetry(ctx) {
				return
			}
			continue
		}
		if !c.processWithRetry(ctx, message) {
			return
		}
	}
}

func (c *ProjectionConsumer) loadCheckpointWithRetry(
	ctx context.Context,
	partition projection.TopicPartition,
	offsetRange projectionOffsetRange,
) (int64, bool) {
	for {
		nextOffset, found, err := c.processor.LoadCheckpoint(
			ctx,
			c.groupID,
			partition.Topic,
			partition.Partition,
		)
		if err == nil {
			resumeOffset, policyErr := projectionCheckpointResumeOffset(nextOffset, found, offsetRange)
			if policyErr == nil {
				return resumeOffset, true
			}
			err = policyErr
		}
		if ctx.Err() != nil {
			return 0, false
		}
		if errors.Is(err, ErrProjectionCheckpointAheadOfTopic) {
			slog.Error(
				"projection checkpoint is ahead of Kafka; refusing seek",
				"topic", partition.Topic,
				"partition", partition.Partition,
				"checkpoint", nextOffset,
				"topicEnd", offsetRange.End,
			)
		} else if errors.Is(err, ErrProjectionCheckpointBehindTopic) {
			slog.Error(
				"projection checkpoint is behind Kafka retention; refusing seek",
				"topic", partition.Topic,
				"partition", partition.Partition,
				"checkpoint", nextOffset,
				"topicStart", offsetRange.First,
			)
		} else {
			slog.Error("projection checkpoint load failed; retrying", "err", err)
		}
		if !waitForRetry(ctx) {
			return 0, false
		}
	}
}

func projectionCheckpointResumeOffset(
	nextOffset int64,
	found bool,
	offsetRange projectionOffsetRange,
) (int64, error) {
	if found && nextOffset < offsetRange.First {
		return 0, fmt.Errorf(
			"%w: checkpoint=%d topicStart=%d",
			ErrProjectionCheckpointBehindTopic,
			nextOffset,
			offsetRange.First,
		)
	}
	if found && nextOffset > offsetRange.End {
		return 0, fmt.Errorf(
			"%w: checkpoint=%d topicEnd=%d",
			ErrProjectionCheckpointAheadOfTopic,
			nextOffset,
			offsetRange.End,
		)
	}
	if !found {
		return offsetRange.First, nil
	}
	return nextOffset, nil
}

func (c *ProjectionConsumer) processWithRetry(ctx context.Context, message segkafka.Message) bool {
	checkpoint := projection.Checkpoint{
		ConsumerGroup: c.groupID,
		Topic:         message.Topic,
		Partition:     message.Partition,
		NextOffset:    message.Offset + 1,
	}
	for {
		err := c.handleWithCheckpoint(ctx, message, checkpoint)
		if err == nil {
			return true
		}
		if errors.Is(err, projection.ErrInvalidEvent) {
			record := projection.InvalidRecord{
				Checkpoint: checkpoint,
				Key:        append([]byte(nil), message.Key...),
				Payload:    append([]byte(nil), message.Value...),
				Reason:     err.Error(),
			}
			for {
				if quarantineErr := c.processor.QuarantineWithCheckpoint(ctx, record); quarantineErr == nil {
					slog.Error(
						"invalid projection event quarantined",
						"topic", message.Topic,
						"partition", message.Partition,
						"offset", message.Offset,
						"reason", err,
					)
					return true
				} else if ctx.Err() == nil {
					slog.Error(
						"projection dead-letter/checkpoint transaction failed; retrying",
						"topic", message.Topic,
						"partition", message.Partition,
						"offset", message.Offset,
						"err", quarantineErr,
					)
				}
				if !waitForRetry(ctx) {
					return false
				}
			}
		}

		if ctx.Err() != nil {
			return false
		}
		slog.Error(
			"projection/checkpoint transaction failed; retrying",
			"topic", message.Topic,
			"partition", message.Partition,
			"offset", message.Offset,
			"err", err,
		)
		if !waitForRetry(ctx) {
			return false
		}
	}
}

func (c *ProjectionConsumer) handleWithCheckpoint(
	ctx context.Context,
	message segkafka.Message,
	checkpoint projection.Checkpoint,
) error {
	expectedSource, found := c.topics.expectedSnapshotSource(message.Topic)
	if !found {
		return fmt.Errorf("%w: unsupported topic %q", projection.ErrInvalidEvent, message.Topic)
	}
	isMarker, marker, err := parseSnapshotMarker(message, expectedSource)
	if err != nil {
		return err
	}
	if isMarker {
		return c.processor.RecordSnapshotMarkerWithCheckpoint(ctx, checkpoint, marker)
	}

	switch message.Topic {
	case c.topics.ChannelNotification:
		var event projection.ChannelNotificationEvent
		if err := json.Unmarshal(message.Value, &event); err != nil {
			return invalidJSON(message.Topic, err)
		}
		if string(message.Key) != fmt.Sprintf("%d:%d", event.AccountID, event.ChannelID) {
			return invalidKey(message.Topic, message.Key)
		}
		return c.processor.ApplyChannelNotificationWithCheckpoint(ctx, event, checkpoint)
	case c.topics.UserProfile:
		var event projection.UserProfileEvent
		if err := json.Unmarshal(message.Value, &event); err != nil {
			return invalidJSON(message.Topic, err)
		}
		if string(message.Key) != fmt.Sprintf("%d", event.UserID) {
			return invalidKey(message.Topic, message.Key)
		}
		return c.processor.ApplyUserProfileWithCheckpoint(ctx, event, checkpoint)
	case c.topics.TeamLifecycle:
		var event projection.TeamLifecycleEvent
		if err := json.Unmarshal(message.Value, &event); err != nil {
			return invalidJSON(message.Topic, err)
		}
		if string(message.Key) != fmt.Sprintf("%d", event.TeamID) {
			return invalidKey(message.Topic, message.Key)
		}
		return c.processor.ApplyTeamLifecycleWithCheckpoint(ctx, event, checkpoint)
	default:
		return fmt.Errorf("%w: unsupported topic %q", projection.ErrInvalidEvent, message.Topic)
	}
}

func (c *ProjectionConsumer) monitorBarrier(
	ctx context.Context,
	ranges map[projection.TopicPartition]projectionOffsetRange,
) {
	partitions := make([]projection.TopicPartition, 0, len(ranges))
	for partition := range ranges {
		partitions = append(partitions, partition)
	}

	ticker := time.NewTicker(barrierPollInterval)
	defer ticker.Stop()
	for {
		checkpoints, err := c.processor.LoadCheckpoints(ctx, c.groupID, partitions)
		c.readiness.Set(err == nil && projectionBarrierComplete(ranges, checkpoints))
		if err != nil && ctx.Err() == nil {
			slog.Error("projection barrier check failed; retrying", "err", err)
		}

		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (c *ProjectionConsumer) Close() error {
	var err error
	c.closeOnce.Do(func() {
		err = c.group.Close()
	})
	return err
}

func projectionBarrierComplete(
	ranges map[projection.TopicPartition]projectionOffsetRange,
	checkpoints map[projection.TopicPartition]projection.CheckpointState,
) bool {
	for partition, offsetRange := range ranges {
		checkpoint, found := checkpoints[partition]
		if !found || checkpoint.NextOffset < offsetRange.End {
			return false
		}
		markerOffset := checkpoint.SnapshotCompletedOffset
		if markerOffset == nil || *markerOffset < offsetRange.First || *markerOffset >= checkpoint.NextOffset {
			return false
		}
	}
	return true
}

type snapshotMarkerPayload struct {
	EventType  string `json:"eventType"`
	Topic      string `json:"topic"`
	Partition  *int   `json:"partition"`
	SnapshotID string `json:"snapshotId"`
	OccurredAt string `json:"occurredAt"`
	Source     string `json:"source"`
}

func parseSnapshotMarker(
	message segkafka.Message,
	expectedSource string,
) (bool, projection.SnapshotMarkerReceipt, error) {
	key := string(message.Key)
	keyCandidate := strings.HasPrefix(key, snapshotMarkerKeyPrefix)

	var marker snapshotMarkerPayload
	if err := json.Unmarshal(message.Value, &marker); err != nil {
		if keyCandidate {
			return true, projection.SnapshotMarkerReceipt{}, invalidJSON(message.Topic, err)
		}
		return false, projection.SnapshotMarkerReceipt{}, nil
	}
	if !keyCandidate && marker.EventType != snapshotMarkerEventType {
		return false, projection.SnapshotMarkerReceipt{}, nil
	}

	expectedKey := fmt.Sprintf("%s%d", snapshotMarkerKeyPrefix, message.Partition)
	if key != expectedKey {
		return true, projection.SnapshotMarkerReceipt{}, fmt.Errorf(
			"%w: snapshot marker key %q does not match %q",
			projection.ErrInvalidEvent,
			key,
			expectedKey,
		)
	}
	if marker.EventType != snapshotMarkerEventType ||
		marker.Topic != message.Topic ||
		marker.Partition == nil ||
		*marker.Partition != message.Partition {
		return true, projection.SnapshotMarkerReceipt{}, fmt.Errorf(
			"%w: invalid snapshot marker routing contract",
			projection.ErrInvalidEvent,
		)
	}
	if !snapshotIDPattern.MatchString(marker.SnapshotID) {
		return true, projection.SnapshotMarkerReceipt{}, fmt.Errorf(
			"%w: invalid snapshot marker snapshotId",
			projection.ErrInvalidEvent,
		)
	}
	occurredAt, err := time.Parse(time.RFC3339Nano, marker.OccurredAt)
	if err != nil || marker.Source != expectedSource {
		return true, projection.SnapshotMarkerReceipt{}, fmt.Errorf(
			"%w: invalid snapshot marker metadata",
			projection.ErrInvalidEvent,
		)
	}
	return true, projection.SnapshotMarkerReceipt{
		Offset:     message.Offset,
		SnapshotID: marker.SnapshotID,
		Source:     marker.Source,
		OccurredAt: occurredAt.UTC(),
	}, nil
}

func invalidJSON(topic string, err error) error {
	return fmt.Errorf("%w: topic %s JSON: %v", projection.ErrInvalidEvent, topic, err)
}

func invalidKey(topic string, key []byte) error {
	return fmt.Errorf("%w: topic %s key %q does not match payload", projection.ErrInvalidEvent, topic, key)
}

func mapKeys(values map[string]struct{}) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	return keys
}

func splitBrokerList(brokers string) []string {
	parts := strings.Split(brokers, ",")
	result := make([]string, 0, len(parts))
	for _, broker := range parts {
		if trimmed := strings.TrimSpace(broker); trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}

func waitForRetry(ctx context.Context) bool {
	timer := time.NewTimer(projectionRetryDelay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
