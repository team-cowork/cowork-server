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
	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/kmsg"
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
var ErrTopicGenerationMismatch = errors.New("projection checkpoint belongs to a different Kafka topic generation")
var ErrProjectionNotCurrent = errors.New("channel membership projection is not at the current Kafka high-watermark")
var ErrProjectionTopologyChanged = errors.New("projection topic topology changed during the consumer generation")

type readinessState interface {
	Set(bool)
}

type offsetSnapshotter interface {
	Snapshot(ctx context.Context, topics []string) (map[TopicPartition]OffsetRange, error)
}

type kafkaOffsetSnapshotter struct {
	client *kgo.Client
}

type brokerTopicMetadata struct {
	topicIDs          map[string]string
	partitionsByTopic map[string][]int
	partitions        map[TopicPartition]struct{}
}

func newKafkaOffsetSnapshotter(brokers []string) (*kafkaOffsetSnapshotter, error) {
	client, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ClientID("cowork-voice-projection-barrier"),
		kgo.RequestTimeoutOverhead(10*time.Second),
	)
	if err != nil {
		return nil, fmt.Errorf("create projection metadata client: %w", err)
	}
	return &kafkaOffsetSnapshotter{client: client}, nil
}

func (s *kafkaOffsetSnapshotter) Snapshot(
	ctx context.Context,
	topics []string,
) (map[TopicPartition]OffsetRange, error) {
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

	requested := make(map[string]struct{}, len(topics))
	for _, topic := range topics {
		requested[topic] = struct{}{}
	}
	brokerMetadata, err := parseBrokerTopicMetadata(metadata, requested)
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
	verifiedMetadata, err := parseBrokerTopicMetadata(verifiedMetadataResponse, requested)
	if err != nil {
		return nil, fmt.Errorf("verify projection topic metadata: %w", err)
	}
	if err := requireStableBrokerMetadata(brokerMetadata, verifiedMetadata); err != nil {
		return nil, err
	}

	ranges := make(map[TopicPartition]OffsetRange, len(brokerMetadata.partitions))
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
		ranges[partition] = OffsetRange{
			First:   first,
			End:     end,
			TopicID: brokerMetadata.topicIDs[partition.Topic],
		}
	}
	return ranges, nil
}

func parseBrokerTopicMetadata(
	metadata *kmsg.MetadataResponse,
	requested map[string]struct{},
) (brokerTopicMetadata, error) {
	result := brokerTopicMetadata{
		topicIDs:          make(map[string]string, len(requested)),
		partitionsByTopic: make(map[string][]int, len(requested)),
		partitions:        make(map[TopicPartition]struct{}),
	}
	for _, topic := range metadata.Topics {
		if topic.Topic == nil {
			return brokerTopicMetadata{}, errors.New("projection metadata response omitted a topic name")
		}
		topicName := *topic.Topic
		if _, ok := requested[topicName]; !ok {
			return brokerTopicMetadata{}, fmt.Errorf("projection metadata returned unexpected topic %s", topicName)
		}
		if _, duplicate := result.topicIDs[topicName]; duplicate {
			return brokerTopicMetadata{}, fmt.Errorf("projection metadata returned duplicate topic %s", topicName)
		}
		if kafkaErr := kerr.ErrorForCode(topic.ErrorCode); kafkaErr != nil {
			return brokerTopicMetadata{}, fmt.Errorf("load metadata for topic %s: %w", topicName, kafkaErr)
		}
		topicUUID := uuid.UUID(topic.TopicID)
		if topicUUID == uuid.Nil {
			return brokerTopicMetadata{}, fmt.Errorf(
				"load metadata for topic %s: broker did not return a topic UUID; Kafka 2.8+ is required",
				topicName,
			)
		}
		result.topicIDs[topicName] = topicUUID.String()
		for _, partition := range topic.Partitions {
			if kafkaErr := kerr.ErrorForCode(partition.ErrorCode); kafkaErr != nil {
				return brokerTopicMetadata{}, fmt.Errorf(
					"load metadata for %s/%d: %w",
					topicName,
					partition.Partition,
					kafkaErr,
				)
			}
			partitionID := int(partition.Partition)
			tp := TopicPartition{Topic: topicName, Partition: partitionID}
			if _, duplicate := result.partitions[tp]; duplicate {
				return brokerTopicMetadata{}, fmt.Errorf(
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
		return brokerTopicMetadata{}, errors.New("projection metadata response is missing a requested topic")
	}
	if len(result.partitions) == 0 {
		return brokerTopicMetadata{}, errors.New("projection topics have no partitions")
	}
	return result, nil
}

func requireStableBrokerMetadata(before, after brokerTopicMetadata) error {
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
				ErrTopicGenerationMismatch,
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

func (s *kafkaOffsetSnapshotter) loadOffsets(
	ctx context.Context,
	partitionsByTopic map[string][]int,
	timestamp int64,
) (map[TopicPartition]int64, error) {
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
	offsets := make(map[TopicPartition]int64)
	for _, topic := range response.Topics {
		for _, partition := range topic.Partitions {
			if kafkaErr := kerr.ErrorForCode(partition.ErrorCode); kafkaErr != nil {
				return nil, fmt.Errorf("load offset for %s/%d: %w", topic.Topic, partition.Partition, kafkaErr)
			}
			tp := TopicPartition{Topic: topic.Topic, Partition: int(partition.Partition)}
			if _, exists := offsets[tp]; exists {
				return nil, fmt.Errorf("duplicate offset response for %s/%d", tp.Topic, tp.Partition)
			}
			offsets[tp] = partition.Offset
		}
	}
	return offsets, nil
}

func (s *kafkaOffsetSnapshotter) Close() {
	s.client.Close()
}

type Consumer struct {
	brokers             []string
	topic               string
	groupID             string
	group               *segkafka.ConsumerGroup
	handler             *EventHandler
	checkpoints         CheckpointStore
	snapshotter         offsetSnapshotter
	readiness           readinessState
	cancel              context.CancelFunc
	done                chan struct{}
	startOnce           sync.Once
	stopOnce            sync.Once
	generationMu        sync.RWMutex
	generationToken     uint64
	generationRanges    map[TopicPartition]OffsetRange
	currentHighMu       sync.Mutex
	currentHighSequence uint64
}

func NewConsumer(
	brokers, topic, groupID string,
	handler *EventHandler,
	checkpoints CheckpointStore,
	readiness readinessState,
) (*Consumer, error) {
	brokerList := splitBrokers(brokers)
	snapshotter, err := newKafkaOffsetSnapshotter(brokerList)
	if err != nil {
		return nil, err
	}
	group, err := segkafka.NewConsumerGroup(segkafka.ConsumerGroupConfig{
		ID:                    groupID,
		Brokers:               brokerList,
		Topics:                []string{topic},
		StartOffset:           segkafka.FirstOffset,
		WatchPartitionChanges: true,
	})
	if err != nil {
		snapshotter.Close()
		return nil, fmt.Errorf("create channel membership consumer group: %w", err)
	}
	return &Consumer{
		brokers:     brokerList,
		topic:       topic,
		groupID:     groupID,
		group:       group,
		handler:     handler,
		checkpoints: checkpoints,
		snapshotter: snapshotter,
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
	consumerCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	ranges, ok := c.snapshotWithRetry(consumerCtx)
	if !ok {
		return
	}
	if !c.initializeCheckpoints(consumerCtx, ranges) {
		return
	}
	generationToken := c.activateGeneration(ranges)
	defer c.deactivateGeneration(generationToken)

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
			defer cancel()
			c.consumePartition(consumerCtx, tp, offsetRange)
		}()
	}

	c.monitorBarrier(consumerCtx, generationToken, ranges)
	cancel()
	readers.Wait()
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
				policyErr := checkpointGenerationError(checkpoint, found, offsetRange)
				resumeOffset := int64(0)
				if policyErr == nil {
					resumeOffset, policyErr = checkpointResumeOffset(nextOffset, found, offsetRange)
				}
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
						offsetRange.TopicID,
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
			if errors.Is(err, ErrTopicGenerationMismatch) {
				slog.Error(
					"channel membership topic generation changed; refusing readiness and seek",
					"topic", partition.Topic,
					"partition", partition.Partition,
					"checkpointTopicId", checkpoint.TopicID,
					"brokerTopicId", offsetRange.TopicID,
					"action", "rebuild the projection and reset its shared checkpoint together",
				)
			} else if errors.Is(err, ErrCheckpointAheadOfTopic) {
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
		if !c.processWithRetry(ctx, message, offsetRange.TopicID) {
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
			policyErr := checkpointGenerationError(checkpoint, found, offsetRange)
			resumeOffset := int64(0)
			if policyErr == nil {
				resumeOffset, policyErr = checkpointResumeOffset(nextOffset, found, offsetRange)
			}
			if policyErr == nil {
				return resumeOffset, true
			}
			err = policyErr
		}
		if ctx.Err() != nil {
			return 0, false
		}
		if errors.Is(err, ErrTopicGenerationMismatch) {
			slog.Error(
				"channel membership topic generation changed; refusing seek",
				"topic", partition.Topic,
				"partition", partition.Partition,
				"checkpointTopicId", checkpoint.TopicID,
				"brokerTopicId", offsetRange.TopicID,
				"action", "rebuild the projection and reset its shared checkpoint together",
			)
		} else if errors.Is(err, ErrCheckpointAheadOfTopic) {
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

func checkpointGenerationError(
	checkpoint CheckpointState,
	found bool,
	offsetRange OffsetRange,
) error {
	if offsetRange.TopicID == "" {
		return fmt.Errorf("%w: broker topic UUID is empty", ErrTopicGenerationMismatch)
	}
	if found && checkpoint.TopicID != offsetRange.TopicID {
		return fmt.Errorf(
			"%w: checkpointTopicId=%q brokerTopicId=%q",
			ErrTopicGenerationMismatch,
			checkpoint.TopicID,
			offsetRange.TopicID,
		)
	}
	return nil
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

func (c *Consumer) processWithRetry(ctx context.Context, message segkafka.Message, topicID string) bool {
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
				topicID,
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
		readinessClosed := false
		for {
			stale, err := c.invalidRecordAlreadyProcessed(ctx, message, topicID)
			if err == nil {
				if stale {
					slog.Warn(
						"stale invalid channel membership event ignored",
						"partition", message.Partition,
						"offset", message.Offset,
					)
					return true
				}
				break
			}
			if !readinessClosed {
				c.closeReadinessForStateGap()
				readinessClosed = true
			}
			if ctx.Err() != nil {
				return false
			}
			slog.Error(
				"channel membership checkpoint preflight failed; retrying",
				"partition", message.Partition,
				"offset", message.Offset,
				"err", err,
			)
			if !waitForRetry(ctx) {
				return false
			}
		}
		if !readinessClosed {
			c.closeReadinessForStateGap()
		}
		for {
			err := c.checkpoints.QuarantineAndAdvance(ctx, DeadLetter{
				ConsumerGroup: c.groupID,
				Topic:         message.Topic,
				Partition:     message.Partition,
				Offset:        message.Offset,
				Key:           append([]byte(nil), message.Key...),
				Payload:       append([]byte(nil), message.Value...),
				Reason:        invalidReason,
			}, topicID, message.Offset+1)
			if err == nil {
				slog.Error(
					"invalid channel membership event quarantined and state gap latched",
					"partition", message.Partition,
					"offset", message.Offset,
					"reason", invalidReason,
				)
				return true
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
	}

	for {
		err := c.checkpoints.Advance(
			ctx,
			c.groupID,
			message.Topic,
			message.Partition,
			topicID,
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

func (c *Consumer) invalidRecordAlreadyProcessed(
	ctx context.Context,
	message segkafka.Message,
	topicID string,
) (bool, error) {
	checkpoint, found, err := c.checkpoints.Load(
		ctx,
		c.groupID,
		message.Topic,
		message.Partition,
	)
	if err != nil || !found {
		return false, err
	}
	if checkpoint.TopicID != topicID {
		return false, fmt.Errorf(
			"%w: checkpointTopicId=%q brokerTopicId=%q",
			ErrTopicGenerationMismatch,
			checkpoint.TopicID,
			topicID,
		)
	}
	return message.Offset < checkpoint.NextOffset, nil
}

func (c *Consumer) monitorBarrier(
	ctx context.Context,
	generationToken uint64,
	expectedRanges map[TopicPartition]OffsetRange,
) {
	ticker := time.NewTicker(barrierPollInterval)
	defer ticker.Stop()
	for {
		err := c.evaluateAndPublishCurrentHigh(ctx, generationToken, expectedRanges)
		if err != nil && ctx.Err() == nil {
			slog.Error("channel membership current-high check failed; retrying", "err", err)
			if errors.Is(err, ErrProjectionTopologyChanged) || errors.Is(err, ErrTopicGenerationMismatch) {
				return
			}
		}

		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// EstablishCurrent synchronously verifies the projection against a fresh broker
// high-watermark. It is used to distinguish an authoritative membership miss
// from a projection that became stale after the route-level readiness check.
func (c *Consumer) EstablishCurrent(ctx context.Context) error {
	generationToken, expectedRanges, active := c.activeGeneration()
	if !active {
		return fmt.Errorf("%w: no active consumer generation", ErrProjectionNotCurrent)
	}
	return c.evaluateAndPublishCurrentHigh(ctx, generationToken, expectedRanges)
}

func (c *Consumer) evaluateAndPublishCurrentHigh(
	ctx context.Context,
	generationToken uint64,
	expectedRanges map[TopicPartition]OffsetRange,
) error {
	c.currentHighMu.Lock()
	c.currentHighSequence++
	sequence := c.currentHighSequence
	c.currentHighMu.Unlock()

	err := c.evaluateCurrentHigh(ctx, expectedRanges)
	c.currentHighMu.Lock()
	if sequence != c.currentHighSequence {
		c.currentHighMu.Unlock()
		return fmt.Errorf("%w: current-high evaluation was superseded", ErrProjectionNotCurrent)
	}
	published := c.setReadinessForGeneration(generationToken, err == nil)
	c.currentHighMu.Unlock()
	if !published {
		return fmt.Errorf("%w: consumer generation changed during current-high evaluation", ErrProjectionNotCurrent)
	}
	return err
}

func (c *Consumer) closeReadinessForStateGap() {
	c.currentHighMu.Lock()
	defer c.currentHighMu.Unlock()
	// Invalidate any broker/checkpoint evaluation that started before this
	// malformed state record was classified, so it cannot reopen readiness.
	c.currentHighSequence++
	c.readiness.Set(false)
}

func (c *Consumer) evaluateCurrentHigh(
	ctx context.Context,
	expectedRanges map[TopicPartition]OffsetRange,
) error {
	currentRanges, err := c.snapshotter.Snapshot(ctx, []string{c.topic})
	if err != nil {
		return fmt.Errorf("%w: capture broker offsets: %v", ErrProjectionNotCurrent, err)
	}
	if err := requireSameTopologyAndGeneration(expectedRanges, currentRanges); err != nil {
		return err
	}
	partitions := make([]TopicPartition, 0, len(currentRanges))
	for partition := range currentRanges {
		partitions = append(partitions, partition)
	}
	checkpoints, err := c.checkpoints.LoadAll(ctx, c.groupID, partitions)
	if err != nil {
		return fmt.Errorf("%w: load checkpoints: %v", ErrProjectionNotCurrent, err)
	}
	if !barrierComplete(currentRanges, checkpoints) {
		return ErrProjectionNotCurrent
	}
	return nil
}

func requireSameTopologyAndGeneration(
	expected map[TopicPartition]OffsetRange,
	current map[TopicPartition]OffsetRange,
) error {
	if len(expected) != len(current) {
		return fmt.Errorf(
			"%w: partitions changed from %d to %d",
			ErrProjectionTopologyChanged,
			len(expected),
			len(current),
		)
	}
	for partition, expectedRange := range expected {
		currentRange, found := current[partition]
		if !found {
			return fmt.Errorf(
				"%w: partition %s/%d disappeared",
				ErrProjectionTopologyChanged,
				partition.Topic,
				partition.Partition,
			)
		}
		if expectedRange.TopicID == "" || currentRange.TopicID != expectedRange.TopicID {
			return fmt.Errorf(
				"%w: %s/%d expectedTopicId=%q currentTopicId=%q; rebuild projection and checkpoint",
				ErrTopicGenerationMismatch,
				partition.Topic,
				partition.Partition,
				expectedRange.TopicID,
				currentRange.TopicID,
			)
		}
	}
	return nil
}

func (c *Consumer) activateGeneration(ranges map[TopicPartition]OffsetRange) uint64 {
	c.generationMu.Lock()
	defer c.generationMu.Unlock()
	c.generationToken++
	c.generationRanges = cloneOffsetRanges(ranges)
	c.readiness.Set(false)
	return c.generationToken
}

func (c *Consumer) deactivateGeneration(generationToken uint64) {
	c.generationMu.Lock()
	defer c.generationMu.Unlock()
	if c.generationToken == generationToken {
		c.generationRanges = nil
		c.readiness.Set(false)
	}
}

func (c *Consumer) activeGeneration() (uint64, map[TopicPartition]OffsetRange, bool) {
	c.generationMu.RLock()
	defer c.generationMu.RUnlock()
	if len(c.generationRanges) == 0 {
		return c.generationToken, nil, false
	}
	return c.generationToken, cloneOffsetRanges(c.generationRanges), true
}

func (c *Consumer) setReadinessForGeneration(generationToken uint64, ready bool) bool {
	c.generationMu.RLock()
	defer c.generationMu.RUnlock()
	if c.generationToken == generationToken && len(c.generationRanges) > 0 {
		c.readiness.Set(ready)
		return true
	}
	return false
}

func cloneOffsetRanges(ranges map[TopicPartition]OffsetRange) map[TopicPartition]OffsetRange {
	cloned := make(map[TopicPartition]OffsetRange, len(ranges))
	for partition, offsetRange := range ranges {
		cloned[partition] = offsetRange
	}
	return cloned
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
		SnapshotID: parsedID.String(),
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
		if closer, ok := c.snapshotter.(interface{ Close() }); ok {
			closer.Close()
		}
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
