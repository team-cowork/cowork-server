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
	"github.com/google/uuid"
	segkafka "github.com/segmentio/kafka-go"
	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/kmsg"
)

const (
	projectionRetryDelay              = time.Second
	barrierPollInterval               = time.Second
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
var ErrProjectionTopologyChanged = errors.New("projection topic topology changed during the consumer generation")

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
	LoadCheckpoint(context.Context, string, string, int) (projection.CheckpointState, bool, error)
	AdvanceCheckpoint(context.Context, projection.Checkpoint) error
	LoadCheckpoints(
		context.Context,
		string,
		[]projection.TopicPartition,
	) (map[projection.TopicPartition]projection.CheckpointState, error)
}

type projectionOffsetRange struct {
	First   int64
	End     int64
	TopicID string
}

type projectionOffsetSnapshotter interface {
	Snapshot(
		ctx context.Context,
		topics []string,
	) (map[projection.TopicPartition]projectionOffsetRange, error)
}

type kafkaProjectionOffsetSnapshotter struct {
	client *kgo.Client
}

type projectionBrokerMetadata struct {
	topicIDs          map[string]string
	partitionsByTopic map[string][]int
	partitions        map[projection.TopicPartition]struct{}
}

func newKafkaProjectionOffsetSnapshotter(brokers []string) (*kafkaProjectionOffsetSnapshotter, error) {
	client, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ClientID("cowork-notification-projection-barrier"),
		kgo.RequestTimeoutOverhead(10*time.Second),
	)
	if err != nil {
		return nil, fmt.Errorf("create projection metadata client: %w", err)
	}
	return &kafkaProjectionOffsetSnapshotter{client: client}, nil
}

func (s *kafkaProjectionOffsetSnapshotter) Snapshot(
	ctx context.Context,
	topics []string,
) (map[projection.TopicPartition]projectionOffsetRange, error) {
	snapshotCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	ctx = snapshotCtx

	metadataRequest := kmsg.NewPtrMetadataRequest()
	metadataRequest.AllowAutoTopicCreation = false
	for _, topic := range topics {
		topicName := topic
		metadataRequest.Topics = append(
			metadataRequest.Topics,
			kmsg.MetadataRequestTopic{Topic: &topicName},
		)
	}
	metadata, err := metadataRequest.RequestWith(ctx, s.client)
	if err != nil {
		return nil, fmt.Errorf("load projection topic metadata: %w", err)
	}

	requestedTopics := make(map[string]struct{}, len(topics))
	for _, topic := range topics {
		requestedTopics[topic] = struct{}{}
	}
	brokerMetadata, err := parseProjectionBrokerMetadata(metadata, requestedTopics)
	if err != nil {
		return nil, err
	}

	firstOffsets, err := s.loadOffsets(ctx, brokerMetadata.partitionsByTopic, -2)
	if err != nil {
		return nil, fmt.Errorf("load projection topic start offsets: %w", err)
	}
	endOffsets, err := s.loadOffsets(ctx, brokerMetadata.partitionsByTopic, -1)
	if err != nil {
		return nil, fmt.Errorf("load projection topic end offsets: %w", err)
	}

	verifiedMetadataResponse, err := metadataRequest.RequestWith(ctx, s.client)
	if err != nil {
		return nil, fmt.Errorf("verify projection topic metadata: %w", err)
	}
	verifiedMetadata, err := parseProjectionBrokerMetadata(verifiedMetadataResponse, requestedTopics)
	if err != nil {
		return nil, fmt.Errorf("verify projection topic metadata: %w", err)
	}
	if err := requireStableProjectionBrokerMetadata(brokerMetadata, verifiedMetadata); err != nil {
		return nil, err
	}

	ranges := make(map[projection.TopicPartition]projectionOffsetRange, len(brokerMetadata.partitions))
	for partition := range brokerMetadata.partitions {
		first, firstFound := firstOffsets[partition]
		end, endFound := endOffsets[partition]
		if !firstFound || !endFound {
			return nil, fmt.Errorf("offset response missing %s/%d", partition.Topic, partition.Partition)
		}
		if first < 0 || end < first {
			return nil, fmt.Errorf(
				"invalid offset range for %s/%d: first=%d end=%d",
				partition.Topic,
				partition.Partition,
				first,
				end,
			)
		}
		ranges[partition] = projectionOffsetRange{
			First:   first,
			End:     end,
			TopicID: brokerMetadata.topicIDs[partition.Topic],
		}
	}
	return ranges, nil
}

func parseProjectionBrokerMetadata(
	metadata *kmsg.MetadataResponse,
	requested map[string]struct{},
) (projectionBrokerMetadata, error) {
	result := projectionBrokerMetadata{
		topicIDs:          make(map[string]string, len(requested)),
		partitionsByTopic: make(map[string][]int, len(requested)),
		partitions:        make(map[projection.TopicPartition]struct{}),
	}
	for _, topic := range metadata.Topics {
		if topic.Topic == nil {
			return projectionBrokerMetadata{}, errors.New("projection metadata response omitted a topic name")
		}
		topicName := *topic.Topic
		if _, ok := requested[topicName]; !ok {
			return projectionBrokerMetadata{}, fmt.Errorf("projection metadata returned unexpected topic %s", topicName)
		}
		if _, duplicate := result.topicIDs[topicName]; duplicate {
			return projectionBrokerMetadata{}, fmt.Errorf("projection metadata returned duplicate topic %s", topicName)
		}
		if kafkaErr := kerr.ErrorForCode(topic.ErrorCode); kafkaErr != nil {
			return projectionBrokerMetadata{}, fmt.Errorf("load metadata for topic %s: %w", topicName, kafkaErr)
		}
		topicUUID := uuid.UUID(topic.TopicID)
		if topicUUID == uuid.Nil {
			return projectionBrokerMetadata{}, fmt.Errorf(
				"load metadata for topic %s: broker did not return a topic UUID; Kafka 2.8+ is required",
				topicName,
			)
		}
		result.topicIDs[topicName] = topicUUID.String()
		if len(topic.Partitions) == 0 {
			return projectionBrokerMetadata{}, fmt.Errorf("projection topic %s has no partitions", topicName)
		}
		for _, partition := range topic.Partitions {
			if kafkaErr := kerr.ErrorForCode(partition.ErrorCode); kafkaErr != nil {
				return projectionBrokerMetadata{}, fmt.Errorf(
					"load metadata for %s/%d: %w",
					topicName,
					partition.Partition,
					kafkaErr,
				)
			}
			partitionID := int(partition.Partition)
			tp := projection.TopicPartition{Topic: topicName, Partition: partitionID}
			if _, duplicate := result.partitions[tp]; duplicate {
				return projectionBrokerMetadata{}, fmt.Errorf(
					"projection metadata returned duplicate partition %s/%d",
					topicName,
					partitionID,
				)
			}
			result.partitionsByTopic[topicName] = append(result.partitionsByTopic[topicName], partitionID)
			result.partitions[tp] = struct{}{}
		}
	}
	if len(result.topicIDs) != len(requested) {
		return projectionBrokerMetadata{}, errors.New("projection metadata response is missing a requested topic")
	}
	return result, nil
}

func requireStableProjectionBrokerMetadata(before, after projectionBrokerMetadata) error {
	if len(before.topicIDs) != len(after.topicIDs) {
		return fmt.Errorf(
			"%w while capturing broker offsets: topic count changed from %d to %d",
			ErrProjectionTopologyChanged,
			len(before.topicIDs),
			len(after.topicIDs),
		)
	}
	for topic, topicID := range before.topicIDs {
		if after.topicIDs[topic] != topicID {
			return fmt.Errorf(
				"%w while capturing broker offsets: %s topic UUID changed",
				projection.ErrTopicGenerationMismatch,
				topic,
			)
		}
	}
	if len(before.partitions) != len(after.partitions) {
		return fmt.Errorf(
			"%w while capturing broker offsets: partitions changed from %d to %d",
			ErrProjectionTopologyChanged,
			len(before.partitions),
			len(after.partitions),
		)
	}
	for partition := range before.partitions {
		if _, found := after.partitions[partition]; !found {
			return fmt.Errorf(
				"%w while capturing broker offsets: partition %s/%d changed",
				ErrProjectionTopologyChanged,
				partition.Topic,
				partition.Partition,
			)
		}
	}
	return nil
}

func (s *kafkaProjectionOffsetSnapshotter) loadOffsets(
	ctx context.Context,
	partitionsByTopic map[string][]int,
	timestamp int64,
) (map[projection.TopicPartition]int64, error) {
	request := kmsg.NewPtrListOffsetsRequest()
	for topic, partitions := range partitionsByTopic {
		requestTopic := kmsg.NewListOffsetsRequestTopic()
		requestTopic.Topic = topic
		for _, partition := range partitions {
			requestPartition := kmsg.NewListOffsetsRequestTopicPartition()
			requestPartition.Partition = int32(partition)
			requestPartition.Timestamp = timestamp
			requestTopic.Partitions = append(requestTopic.Partitions, requestPartition)
		}
		request.Topics = append(request.Topics, requestTopic)
	}
	response, err := request.RequestWith(ctx, s.client)
	if err != nil {
		return nil, err
	}
	offsets := make(map[projection.TopicPartition]int64)
	for _, topic := range response.Topics {
		for _, partition := range topic.Partitions {
			if kafkaErr := kerr.ErrorForCode(partition.ErrorCode); kafkaErr != nil {
				return nil, fmt.Errorf("load offset for %s/%d: %w", topic.Topic, partition.Partition, kafkaErr)
			}
			tp := projection.TopicPartition{Topic: topic.Topic, Partition: int(partition.Partition)}
			if _, exists := offsets[tp]; exists {
				return nil, fmt.Errorf("duplicate offset response for %s/%d", tp.Topic, tp.Partition)
			}
			offsets[tp] = partition.Offset
		}
	}
	return offsets, nil
}

func (s *kafkaProjectionOffsetSnapshotter) Close() {
	s.client.Close()
}

type ProjectionConsumer struct {
	brokers        []string
	groupID        string
	topics         ProjectionTopics
	group          *segkafka.ConsumerGroup
	processor      projectionProcessor
	snapshotter    projectionOffsetSnapshotter
	readiness      projectionReadiness
	closeOnce      sync.Once
	readinessMu    sync.Mutex
	readinessEpoch uint64
}

func NewProjectionConsumer(
	brokers string,
	groupID string,
	topics ProjectionTopics,
	processor projectionProcessor,
	readiness projectionReadiness,
) (*ProjectionConsumer, error) {
	brokerList := splitBrokerList(brokers)
	snapshotter, err := newKafkaProjectionOffsetSnapshotter(brokerList)
	if err != nil {
		return nil, err
	}
	group, err := segkafka.NewConsumerGroup(segkafka.ConsumerGroupConfig{
		ID:                    groupID,
		Brokers:               brokerList,
		Topics:                topics.all(),
		StartOffset:           segkafka.FirstOffset,
		WatchPartitionChanges: true,
	})
	if err != nil {
		snapshotter.Close()
		return nil, fmt.Errorf("create notification projection consumer group: %w", err)
	}
	return &ProjectionConsumer{
		brokers:     brokerList,
		groupID:     groupID,
		topics:      topics,
		group:       group,
		processor:   processor,
		snapshotter: snapshotter,
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
	consumerCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	ranges, ok := c.snapshotWithRetry(consumerCtx)
	if !ok || !c.initializeCheckpoints(consumerCtx, ranges) {
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
				defer cancel()
				c.consumePartition(consumerCtx, partition, offsetRange)
			}()
		}
	}

	c.monitorBarrier(consumerCtx, ranges)
	cancel()
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
			checkpoint, found, err := c.processor.LoadCheckpoint(
				ctx,
				c.groupID,
				partition.Topic,
				partition.Partition,
			)
			nextOffset := checkpoint.NextOffset
			if err == nil {
				resumeOffset, policyErr := projectionCheckpointResumeOffset(checkpoint, found, offsetRange)
				if policyErr != nil {
					err = policyErr
				} else if found && resumeOffset == nextOffset {
					break
				} else {
					err = c.processor.AdvanceCheckpoint(ctx, projection.Checkpoint{
						ConsumerGroup: c.groupID,
						Topic:         partition.Topic,
						Partition:     partition.Partition,
						TopicID:       offsetRange.TopicID,
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
			if errors.Is(err, projection.ErrTopicGenerationMismatch) {
				slog.Error(
					"projection topic generation changed; refusing readiness and seek",
					"topic", partition.Topic,
					"partition", partition.Partition,
					"checkpointTopicId", checkpoint.TopicID,
					"brokerTopicId", offsetRange.TopicID,
					"action", "rebuild projection tables and shared checkpoints together; do not reset offsets alone",
				)
			} else if errors.Is(err, ErrProjectionCheckpointAheadOfTopic) {
				slog.Error(
					"projection checkpoint is ahead of Kafka; refusing readiness and seek",
					"topic", partition.Topic,
					"partition", partition.Partition,
					"checkpoint", nextOffset,
					"topicEnd", offsetRange.End,
					"action", "rebuild projection tables and shared checkpoints together; do not reset offsets alone",
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
		if !c.processWithRetry(ctx, message, offsetRange.TopicID) {
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
		checkpoint, found, err := c.processor.LoadCheckpoint(
			ctx,
			c.groupID,
			partition.Topic,
			partition.Partition,
		)
		nextOffset := checkpoint.NextOffset
		if err == nil {
			resumeOffset, policyErr := projectionCheckpointResumeOffset(checkpoint, found, offsetRange)
			if policyErr == nil {
				return resumeOffset, true
			}
			err = policyErr
		}
		if ctx.Err() != nil {
			return 0, false
		}
		if errors.Is(err, projection.ErrTopicGenerationMismatch) {
			slog.Error(
				"projection topic generation changed; refusing seek",
				"topic", partition.Topic,
				"partition", partition.Partition,
				"checkpointTopicId", checkpoint.TopicID,
				"brokerTopicId", offsetRange.TopicID,
				"action", "rebuild projection tables and shared checkpoints together; do not reset offsets alone",
			)
		} else if errors.Is(err, ErrProjectionCheckpointAheadOfTopic) {
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
	checkpoint projection.CheckpointState,
	found bool,
	offsetRange projectionOffsetRange,
) (int64, error) {
	if offsetRange.TopicID == "" {
		return 0, fmt.Errorf("%w: broker topic UUID is empty", projection.ErrTopicGenerationMismatch)
	}
	if found && checkpoint.TopicID != offsetRange.TopicID {
		return 0, fmt.Errorf(
			"%w: checkpointTopicId=%q brokerTopicId=%q",
			projection.ErrTopicGenerationMismatch,
			checkpoint.TopicID,
			offsetRange.TopicID,
		)
	}
	nextOffset := checkpoint.NextOffset
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

func (c *ProjectionConsumer) processWithRetry(
	ctx context.Context,
	message segkafka.Message,
	topicID string,
) bool {
	checkpoint := projection.Checkpoint{
		ConsumerGroup: c.groupID,
		Topic:         message.Topic,
		Partition:     message.Partition,
		TopicID:       topicID,
		NextOffset:    message.Offset + 1,
	}
	for {
		err := c.handleWithCheckpoint(ctx, message, checkpoint)
		if err == nil {
			return true
		}
		if errors.Is(err, projection.ErrInvalidEvent) {
			_, latchStateGap := c.topics.expectedSnapshotSource(message.Topic)
			readinessClosed := false
			for {
				stale, preflightErr := c.invalidRecordAlreadyProcessed(ctx, checkpoint, message.Offset)
				if preflightErr == nil {
					if stale {
						slog.Warn(
							"stale invalid projection event ignored",
							"topic", message.Topic,
							"partition", message.Partition,
							"offset", message.Offset,
						)
						return true
					}
					break
				}
				if latchStateGap && !readinessClosed {
					c.closeReadinessForStateGap()
					readinessClosed = true
				}
				if ctx.Err() != nil {
					return false
				}
				slog.Error(
					"projection checkpoint preflight failed; retrying",
					"topic", message.Topic,
					"partition", message.Partition,
					"offset", message.Offset,
					"err", preflightErr,
				)
				if !waitForRetry(ctx) {
					return false
				}
			}
			if latchStateGap && !readinessClosed {
				c.closeReadinessForStateGap()
			}
			record := projection.InvalidRecord{
				Checkpoint:    checkpoint,
				Key:           append([]byte(nil), message.Key...),
				Payload:       append([]byte(nil), message.Value...),
				Reason:        err.Error(),
				LatchStateGap: latchStateGap,
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

func (c *ProjectionConsumer) invalidRecordAlreadyProcessed(
	ctx context.Context,
	checkpoint projection.Checkpoint,
	recordOffset int64,
) (bool, error) {
	stored, found, err := c.processor.LoadCheckpoint(
		ctx,
		checkpoint.ConsumerGroup,
		checkpoint.Topic,
		checkpoint.Partition,
	)
	if err != nil || !found {
		return false, err
	}
	if stored.TopicID != checkpoint.TopicID {
		return false, fmt.Errorf(
			"%w: checkpointTopicId=%q brokerTopicId=%q",
			projection.ErrTopicGenerationMismatch,
			stored.TopicID,
			checkpoint.TopicID,
		)
	}
	return recordOffset < stored.NextOffset, nil
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
	startupRanges map[projection.TopicPartition]projectionOffsetRange,
) {
	ticker := time.NewTicker(barrierPollInterval)
	defer ticker.Stop()
	for {
		readinessEpoch := c.currentReadinessEpoch()
		currentRanges, rangeErr := c.snapshotter.Snapshot(ctx, c.topics.all())
		if rangeErr != nil {
			c.readiness.Set(false)
			if ctx.Err() == nil {
				slog.Error("projection current high-watermark check failed; retrying", "err", rangeErr)
			}
		} else if topologyErr := requireSameProjectionTopologyAndGeneration(startupRanges, currentRanges); topologyErr != nil {
			c.readiness.Set(false)
			if ctx.Err() == nil {
				slog.Error(
					"projection topic generation or topology changed; refusing readiness and stopping readers",
					"err", topologyErr,
					"action", "rebuild projection tables and shared checkpoints together on generation change",
				)
			}
			return
		} else {
			partitions := make([]projection.TopicPartition, 0, len(currentRanges))
			for partition := range currentRanges {
				partitions = append(partitions, partition)
			}
			checkpoints, err := c.processor.LoadCheckpoints(ctx, c.groupID, partitions)
			c.publishReadiness(
				readinessEpoch,
				err == nil && projectionBarrierComplete(currentRanges, checkpoints),
			)
			if err != nil && ctx.Err() == nil {
				slog.Error("projection barrier check failed; retrying", "err", err)
			}
		}

		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (c *ProjectionConsumer) closeReadinessForStateGap() {
	c.readinessMu.Lock()
	defer c.readinessMu.Unlock()
	c.readinessEpoch++
	c.readiness.Set(false)
}

func (c *ProjectionConsumer) currentReadinessEpoch() uint64 {
	c.readinessMu.Lock()
	defer c.readinessMu.Unlock()
	return c.readinessEpoch
}

func (c *ProjectionConsumer) publishReadiness(epoch uint64, ready bool) {
	c.readinessMu.Lock()
	defer c.readinessMu.Unlock()
	if epoch != c.readinessEpoch {
		ready = false
	}
	c.readiness.Set(ready)
}

func (c *ProjectionConsumer) Close() error {
	var err error
	c.closeOnce.Do(func() {
		err = c.group.Close()
		if snapshotter, ok := c.snapshotter.(*kafkaProjectionOffsetSnapshotter); ok {
			snapshotter.Close()
		}
	})
	return err
}

func projectionBarrierComplete(
	ranges map[projection.TopicPartition]projectionOffsetRange,
	checkpoints map[projection.TopicPartition]projection.CheckpointState,
) bool {
	for partition, offsetRange := range ranges {
		checkpoint, found := checkpoints[partition]
		if !found || offsetRange.TopicID == "" || checkpoint.TopicID != offsetRange.TopicID ||
			checkpoint.NextOffset != offsetRange.End || checkpoint.InvalidRecordOffset != nil {
			return false
		}
		markerOffset := checkpoint.SnapshotCompletedOffset
		if markerOffset == nil || *markerOffset < offsetRange.First || *markerOffset >= checkpoint.NextOffset {
			return false
		}
	}
	return true
}

func requireSameProjectionTopologyAndGeneration(
	left map[projection.TopicPartition]projectionOffsetRange,
	right map[projection.TopicPartition]projectionOffsetRange,
) error {
	if len(left) != len(right) {
		return fmt.Errorf(
			"%w: partitions changed from %d to %d",
			ErrProjectionTopologyChanged,
			len(left),
			len(right),
		)
	}
	for partition, leftRange := range left {
		rightRange, found := right[partition]
		if !found {
			return fmt.Errorf(
				"%w: partition %s/%d disappeared",
				ErrProjectionTopologyChanged,
				partition.Topic,
				partition.Partition,
			)
		}
		if leftRange.TopicID == "" || rightRange.TopicID != leftRange.TopicID {
			return fmt.Errorf(
				"%w: %s/%d expectedTopicId=%q currentTopicId=%q",
				projection.ErrTopicGenerationMismatch,
				partition.Topic,
				partition.Partition,
				leftRange.TopicID,
				rightRange.TopicID,
			)
		}
	}
	return nil
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
	parsedSnapshotID, err := uuid.Parse(marker.SnapshotID)
	if err != nil || parsedSnapshotID == uuid.Nil {
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
		SnapshotID: parsedSnapshotID.String(),
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
