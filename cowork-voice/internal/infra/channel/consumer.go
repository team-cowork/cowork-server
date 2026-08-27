package channel

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	segkafka "github.com/segmentio/kafka-go"
)

const (
	projectionRetryDelay         = time.Second
	barrierPollInterval          = 250 * time.Millisecond
	projectionReaderBytes        = 10e6
	snapshotMarkerKeyPrefix      = "__cowork_projection_snapshot_complete__:"
	snapshotMarkerEventType      = "PROJECTION_SNAPSHOT_COMPLETED"
	snapshotMarkerExpectedSource = "cowork-channel"
)

var ErrCheckpointAheadOfTopic = errors.New("projection checkpoint is ahead of the topic end")
var ErrCheckpointBehindTopic = errors.New("projection checkpoint is behind the topic start")

type readinessState interface {
	Set(bool)
}

type offsetSnapshotter interface {
	Snapshot(ctx context.Context, topics []string) (map[TopicPartition]OffsetRange, error)
}

type kafkaOffsetSnapshotter struct {
	client *segkafka.Client
}

func newKafkaOffsetSnapshotter(brokers []string) *kafkaOffsetSnapshotter {
	return &kafkaOffsetSnapshotter{client: &segkafka.Client{
		Addr:    segkafka.TCP(brokers...),
		Timeout: 10 * time.Second,
	}}
}

func (s *kafkaOffsetSnapshotter) Snapshot(
	ctx context.Context,
	topics []string,
) (map[TopicPartition]OffsetRange, error) {
	metadata, err := s.client.Metadata(ctx, &segkafka.MetadataRequest{Topics: topics})
	if err != nil {
		return nil, fmt.Errorf("load projection topic metadata: %w", err)
	}

	requests := make(map[string][]segkafka.OffsetRequest, len(topics))
	expected := make(map[TopicPartition]struct{})
	for _, topic := range metadata.Topics {
		if topic.Error != nil {
			return nil, fmt.Errorf("load metadata for topic %s: %w", topic.Name, topic.Error)
		}
		for _, partition := range topic.Partitions {
			if partition.Error != nil {
				return nil, fmt.Errorf(
					"load metadata for %s/%d: %w",
					topic.Name,
					partition.ID,
					partition.Error,
				)
			}
			requests[topic.Name] = append(
				requests[topic.Name],
				segkafka.FirstOffsetOf(partition.ID),
				segkafka.LastOffsetOf(partition.ID),
			)
			expected[TopicPartition{Topic: topic.Name, Partition: partition.ID}] = struct{}{}
		}
	}
	if len(expected) == 0 {
		return nil, errors.New("projection topics have no partitions")
	}

	offsets, err := s.client.ListOffsets(ctx, &segkafka.ListOffsetsRequest{Topics: requests})
	if err != nil {
		return nil, fmt.Errorf("load projection topic offsets: %w", err)
	}
	ranges := make(map[TopicPartition]OffsetRange, len(expected))
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
			tp := TopicPartition{Topic: topic, Partition: partition.Partition}
			ranges[tp] = OffsetRange{First: partition.FirstOffset, End: partition.LastOffset}
		}
	}
	for partition := range expected {
		if _, ok := ranges[partition]; !ok {
			return nil, fmt.Errorf("offset response missing %s/%d", partition.Topic, partition.Partition)
		}
	}
	return ranges, nil
}

type Consumer struct {
	brokers     []string
	topic       string
	groupID     string
	group       *segkafka.ConsumerGroup
	handler     *EventHandler
	checkpoints CheckpointStore
	snapshotter offsetSnapshotter
	readiness   readinessState
	cancel      context.CancelFunc
	done        chan struct{}
	startOnce   sync.Once
	stopOnce    sync.Once
}

func NewConsumer(
	brokers, topic, groupID string,
	handler *EventHandler,
	checkpoints CheckpointStore,
	readiness readinessState,
) (*Consumer, error) {
	brokerList := splitBrokers(brokers)
	group, err := segkafka.NewConsumerGroup(segkafka.ConsumerGroupConfig{
		ID:                    groupID,
		Brokers:               brokerList,
		Topics:                []string{topic},
		StartOffset:           segkafka.FirstOffset,
		WatchPartitionChanges: true,
	})
	if err != nil {
		return nil, fmt.Errorf("create channel membership consumer group: %w", err)
	}
	return &Consumer{
		brokers:     brokerList,
		topic:       topic,
		groupID:     groupID,
		group:       group,
		handler:     handler,
		checkpoints: checkpoints,
		snapshotter: newKafkaOffsetSnapshotter(brokerList),
		readiness:   readiness,
		done:        make(chan struct{}),
	}, nil
}

func (c *Consumer) Start(parent context.Context) {
	c.startOnce.Do(func() {
		ctx, cancel := context.WithCancel(parent)
		c.cancel = cancel
		go c.run(ctx)
	})
}

func (c *Consumer) run(ctx context.Context) {
	defer close(c.done)
	defer c.readiness.Set(false)
	for {
		generation, err := c.group.Next(ctx)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, segkafka.ErrGroupClosed) {
				return
			}
			slog.Error("channel membership consumer group failed", "err", err)
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

func (c *Consumer) consumeGeneration(
	ctx context.Context,
	assignments map[string][]segkafka.PartitionAssignment,
) {
	ranges, ok := c.snapshotWithRetry(ctx)
	if !ok {
		return
	}
	if !c.initializeCheckpoints(ctx, ranges) {
		return
	}

	var readers sync.WaitGroup
	for _, assignment := range assignments[c.topic] {
		tp := TopicPartition{Topic: c.topic, Partition: assignment.ID}
		offsetRange, exists := ranges[tp]
		if !exists {
			slog.Error("assigned partition missing from barrier", "topic", c.topic, "partition", assignment.ID)
			continue
		}
		readers.Add(1)
		go func() {
			defer readers.Done()
			c.consumePartition(ctx, tp, offsetRange)
		}()
	}

	c.monitorBarrier(ctx, ranges)
	readers.Wait()
	c.readiness.Set(false)
}

func (c *Consumer) snapshotWithRetry(ctx context.Context) (map[TopicPartition]OffsetRange, bool) {
	for {
		ranges, err := c.snapshotter.Snapshot(ctx, []string{c.topic})
		if err == nil {
			return ranges, true
		}
		if ctx.Err() != nil {
			return nil, false
		}
		slog.Error("channel membership barrier capture failed; retrying", "err", err)
		if !waitForRetry(ctx) {
			return nil, false
		}
	}
}

func (c *Consumer) initializeCheckpoints(ctx context.Context, ranges map[TopicPartition]OffsetRange) bool {
	for partition, offsetRange := range ranges {
		for {
			checkpoint, found, err := c.checkpoints.Load(
				ctx,
				c.groupID,
				partition.Topic,
				partition.Partition,
			)
			nextOffset := checkpoint.NextOffset
			if err == nil {
				resumeOffset, policyErr := checkpointResumeOffset(nextOffset, found, offsetRange)
				if policyErr != nil {
					err = policyErr
				} else if found && resumeOffset == nextOffset {
					break
				} else {
					err = c.checkpoints.Advance(
						ctx,
						c.groupID,
						partition.Topic,
						partition.Partition,
						resumeOffset,
					)
				}
			}
			if err == nil {
				break
			}
			if ctx.Err() != nil {
				return false
			}
			if errors.Is(err, ErrCheckpointAheadOfTopic) {
				slog.Error(
					"channel membership checkpoint is ahead of Kafka; refusing readiness and seek",
					"topic", partition.Topic,
					"partition", partition.Partition,
					"checkpoint", nextOffset,
					"topicEnd", offsetRange.End,
					"action", "verify topic recreation and reset the shared projection checkpoint",
				)
			} else if errors.Is(err, ErrCheckpointBehindTopic) {
				slog.Error(
					"channel membership checkpoint is behind Kafka retention; refusing readiness and seek",
					"topic", partition.Topic,
					"partition", partition.Partition,
					"checkpoint", nextOffset,
					"topicStart", offsetRange.First,
					"action", "rebuild the projection and reset its shared checkpoint together",
				)
			} else {
				slog.Error(
					"channel membership checkpoint initialization failed; retrying",
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

func (c *Consumer) consumePartition(ctx context.Context, partition TopicPartition, offsetRange OffsetRange) {
	nextOffset, ok := c.loadCheckpointWithRetry(ctx, partition, offsetRange)
	if !ok {
		return
	}
	reader := segkafka.NewReader(segkafka.ReaderConfig{
		Brokers:   c.brokers,
		Topic:     partition.Topic,
		Partition: partition.Partition,
		MinBytes:  1,
		MaxBytes:  projectionReaderBytes,
	})
	defer func() { _ = reader.Close() }()
	if err := reader.SetOffset(nextOffset); err != nil {
		slog.Error(
			"channel membership checkpoint seek failed",
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
				"channel membership event fetch failed; retrying",
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

func (c *Consumer) loadCheckpointWithRetry(
	ctx context.Context,
	partition TopicPartition,
	offsetRange OffsetRange,
) (int64, bool) {
	for {
		checkpoint, found, err := c.checkpoints.Load(
			ctx,
			c.groupID,
			partition.Topic,
			partition.Partition,
		)
		nextOffset := checkpoint.NextOffset
		if err == nil {
			resumeOffset, policyErr := checkpointResumeOffset(nextOffset, found, offsetRange)
			if policyErr == nil {
				return resumeOffset, true
			}
			err = policyErr
		}
		if ctx.Err() != nil {
			return 0, false
		}
		if errors.Is(err, ErrCheckpointAheadOfTopic) {
			slog.Error(
				"channel membership checkpoint is ahead of Kafka; refusing seek",
				"topic", partition.Topic,
				"partition", partition.Partition,
				"checkpoint", nextOffset,
				"topicEnd", offsetRange.End,
			)
		} else if errors.Is(err, ErrCheckpointBehindTopic) {
			slog.Error(
				"channel membership checkpoint is behind Kafka retention; refusing seek",
				"topic", partition.Topic,
				"partition", partition.Partition,
				"checkpoint", nextOffset,
				"topicStart", offsetRange.First,
			)
		} else {
			slog.Error("channel membership checkpoint load failed; retrying", "err", err)
		}
		if !waitForRetry(ctx) {
			return 0, false
		}
	}
}

func checkpointResumeOffset(nextOffset int64, found bool, offsetRange OffsetRange) (int64, error) {
	if found && nextOffset < offsetRange.First {
		return 0, fmt.Errorf(
			"%w: checkpoint=%d topicStart=%d",
			ErrCheckpointBehindTopic,
			nextOffset,
			offsetRange.First,
		)
	}
	if found && nextOffset > offsetRange.End {
		return 0, fmt.Errorf(
			"%w: checkpoint=%d topicEnd=%d",
			ErrCheckpointAheadOfTopic,
			nextOffset,
			offsetRange.End,
		)
	}
	if !found {
		return offsetRange.First, nil
	}
	return nextOffset, nil
}

func (c *Consumer) processWithRetry(ctx context.Context, message segkafka.Message) bool {
	invalidReason := ""
	isMarker, marker, markerErr := parseSnapshotMarker(message)
	if markerErr != nil {
		invalidReason = markerErr.Error()
	} else if isMarker {
		for {
			err := c.checkpoints.RecordSnapshotMarker(
				ctx,
				c.groupID,
				message.Topic,
				message.Partition,
				message.Offset+1,
				marker,
			)
			if err == nil {
				return true
			}
			if ctx.Err() != nil {
				return false
			}
			slog.Error(
				"channel membership snapshot marker/checkpoint update failed; retrying",
				"partition", message.Partition,
				"offset", message.Offset,
				"err", err,
			)
			if !waitForRetry(ctx) {
				return false
			}
		}
	} else {
		for {
			err := c.handler.Handle(ctx, string(message.Key), message.Value)
			if err == nil {
				break
			}
			if errors.Is(err, ErrInvalidEvent) {
				invalidReason = err.Error()
				break
			}
			if ctx.Err() != nil {
				return false
			}
			slog.Error(
				"channel membership projection update failed; retrying",
				"partition", message.Partition,
				"offset", message.Offset,
				"err", err,
			)
			if !waitForRetry(ctx) {
				return false
			}
		}
	}

	if invalidReason != "" {
		for {
			err := c.checkpoints.Quarantine(ctx, DeadLetter{
				ConsumerGroup: c.groupID,
				Topic:         message.Topic,
				Partition:     message.Partition,
				Offset:        message.Offset,
				Key:           append([]byte(nil), message.Key...),
				Payload:       append([]byte(nil), message.Value...),
				Reason:        invalidReason,
			})
			if err == nil {
				break
			}
			if ctx.Err() != nil {
				return false
			}
			slog.Error(
				"invalid channel membership event quarantine failed; retrying",
				"partition", message.Partition,
				"offset", message.Offset,
				"err", err,
			)
			if !waitForRetry(ctx) {
				return false
			}
		}
		slog.Error(
			"invalid channel membership event quarantined",
			"partition", message.Partition,
			"offset", message.Offset,
			"reason", invalidReason,
		)
	}

	for {
		err := c.checkpoints.Advance(
			ctx,
			c.groupID,
			message.Topic,
			message.Partition,
			message.Offset+1,
		)
		if err == nil {
			return true
		}
		if ctx.Err() != nil {
			return false
		}
		slog.Error(
			"channel membership checkpoint update failed; retrying",
			"partition", message.Partition,
			"offset", message.Offset,
			"err", err,
		)
		if !waitForRetry(ctx) {
			return false
		}
	}
}

func (c *Consumer) monitorBarrier(ctx context.Context, ranges map[TopicPartition]OffsetRange) {
	partitions := make([]TopicPartition, 0, len(ranges))
	for partition := range ranges {
		partitions = append(partitions, partition)
	}

	ticker := time.NewTicker(barrierPollInterval)
	defer ticker.Stop()
	for {
		checkpoints, err := c.checkpoints.LoadAll(ctx, c.groupID, partitions)
		c.readiness.Set(err == nil && barrierComplete(ranges, checkpoints))
		if err != nil && ctx.Err() == nil {
			slog.Error("channel membership barrier check failed; retrying", "err", err)
		}

		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

type snapshotMarkerPayload struct {
	EventType  string `json:"eventType"`
	Topic      string `json:"topic"`
	Partition  *int   `json:"partition"`
	SnapshotID string `json:"snapshotId"`
	OccurredAt string `json:"occurredAt"`
	Source     string `json:"source"`
}

func parseSnapshotMarker(message segkafka.Message) (bool, SnapshotMarkerReceipt, error) {
	key := string(message.Key)
	keyCandidate := strings.HasPrefix(key, snapshotMarkerKeyPrefix)

	var marker snapshotMarkerPayload
	if err := json.Unmarshal(message.Value, &marker); err != nil {
		if keyCandidate {
			return true, SnapshotMarkerReceipt{}, fmt.Errorf("%w: decode snapshot marker: %v", ErrInvalidEvent, err)
		}
		return false, SnapshotMarkerReceipt{}, nil
	}
	if !keyCandidate && marker.EventType != snapshotMarkerEventType {
		return false, SnapshotMarkerReceipt{}, nil
	}

	expectedKey := fmt.Sprintf("%s%d", snapshotMarkerKeyPrefix, message.Partition)
	if key != expectedKey {
		return true, SnapshotMarkerReceipt{}, fmt.Errorf(
			"%w: snapshot marker key %q does not match %q",
			ErrInvalidEvent,
			key,
			expectedKey,
		)
	}
	if marker.EventType != snapshotMarkerEventType ||
		marker.Topic != message.Topic ||
		marker.Partition == nil ||
		*marker.Partition != message.Partition {
		return true, SnapshotMarkerReceipt{}, fmt.Errorf("%w: invalid snapshot marker routing contract", ErrInvalidEvent)
	}
	parsedID, err := uuid.Parse(marker.SnapshotID)
	if err != nil || parsedID == uuid.Nil || parsedID.String() != strings.ToLower(marker.SnapshotID) {
		return true, SnapshotMarkerReceipt{}, fmt.Errorf("%w: invalid snapshot marker snapshotId", ErrInvalidEvent)
	}
	occurredAt, err := time.Parse(time.RFC3339Nano, marker.OccurredAt)
	if err != nil || marker.Source != snapshotMarkerExpectedSource {
		return true, SnapshotMarkerReceipt{}, fmt.Errorf("%w: invalid snapshot marker metadata", ErrInvalidEvent)
	}
	return true, SnapshotMarkerReceipt{
		Offset:     message.Offset,
		SnapshotID: marker.SnapshotID,
		Source:     marker.Source,
		OccurredAt: occurredAt.UTC(),
	}, nil
}

func (c *Consumer) Stop() error {
	c.stopOnce.Do(func() {
		if c.cancel != nil {
			c.cancel()
		}
		_ = c.group.Close()
	})
	<-c.done
	return nil
}

func splitBrokers(brokers string) []string {
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
