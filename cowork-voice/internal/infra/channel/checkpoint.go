package channel

import (
	"context"
	"errors"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

const (
	CollectionProjectionCheckpoints = "projection_checkpoints"
	CollectionProjectionDeadLetters = "projection_dead_letters"
)

type TopicPartition struct {
	Topic     string
	Partition int
}

type OffsetRange struct {
	First int64
	End   int64
}

type CheckpointState struct {
	NextOffset              int64  `bson:"next_offset"`
	SnapshotCompletedOffset *int64 `bson:"snapshot_completed_offset,omitempty"`
}

type SnapshotMarkerReceipt struct {
	Offset     int64
	SnapshotID string
	Source     string
	OccurredAt time.Time
}

type DeadLetter struct {
	ConsumerGroup string    `bson:"consumer_group"`
	Topic         string    `bson:"topic"`
	Partition     int       `bson:"partition"`
	Offset        int64     `bson:"offset"`
	Key           []byte    `bson:"event_key"`
	Payload       []byte    `bson:"payload"`
	Reason        string    `bson:"reason"`
	CreatedAt     time.Time `bson:"created_at"`
}

type CheckpointStore interface {
	Load(ctx context.Context, group, topic string, partition int) (CheckpointState, bool, error)
	Advance(ctx context.Context, group, topic string, partition int, nextOffset int64) error
	RecordSnapshotMarker(
		ctx context.Context,
		group, topic string,
		partition int,
		nextOffset int64,
		marker SnapshotMarkerReceipt,
	) error
	LoadAll(
		ctx context.Context,
		group string,
		partitions []TopicPartition,
	) (map[TopicPartition]CheckpointState, error)
	Quarantine(ctx context.Context, deadLetter DeadLetter) error
}

type MongoCheckpointStore struct {
	checkpoints *mongo.Collection
	deadLetters *mongo.Collection
}

func NewMongoCheckpointStore(db *mongo.Database) *MongoCheckpointStore {
	return &MongoCheckpointStore{
		checkpoints: db.Collection(CollectionProjectionCheckpoints),
		deadLetters: db.Collection(CollectionProjectionDeadLetters),
	}
}

func CreateCheckpointIndexes(ctx context.Context, db *mongo.Database) error {
	_, err := db.Collection(CollectionProjectionCheckpoints).Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys: bson.D{
			{Key: "consumer_group", Value: 1},
			{Key: "topic", Value: 1},
			{Key: "partition", Value: 1},
		},
		Options: options.Index().SetUnique(true),
	})
	if err != nil {
		return fmt.Errorf("projection checkpoint index creation failed: %w", err)
	}
	_, err = db.Collection(CollectionProjectionDeadLetters).Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys: bson.D{
			{Key: "consumer_group", Value: 1},
			{Key: "topic", Value: 1},
			{Key: "partition", Value: 1},
			{Key: "offset", Value: 1},
		},
		Options: options.Index().SetUnique(true),
	})
	if err != nil {
		return fmt.Errorf("projection dead-letter index creation failed: %w", err)
	}
	return nil
}

func (s *MongoCheckpointStore) Load(
	ctx context.Context,
	group, topic string,
	partition int,
) (CheckpointState, bool, error) {
	var row CheckpointState
	err := s.checkpoints.FindOne(ctx, checkpointFilter(group, topic, partition)).Decode(&row)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return CheckpointState{}, false, nil
	}
	if err != nil {
		return CheckpointState{}, false, fmt.Errorf("load projection checkpoint: %w", err)
	}
	return row, true, nil
}

func (s *MongoCheckpointStore) RecordSnapshotMarker(
	ctx context.Context,
	group, topic string,
	partition int,
	nextOffset int64,
	marker SnapshotMarkerReceipt,
) error {
	_, err := s.checkpoints.UpdateOne(
		ctx,
		checkpointFilter(group, topic, partition),
		snapshotMarkerCheckpointUpdate(group, topic, partition, nextOffset, marker),
		options.UpdateOne().SetUpsert(true),
	)
	if err != nil {
		return fmt.Errorf("record projection snapshot marker: %w", err)
	}
	return nil
}

func (s *MongoCheckpointStore) Advance(
	ctx context.Context,
	group, topic string,
	partition int,
	nextOffset int64,
) error {
	_, err := s.checkpoints.UpdateOne(
		ctx,
		checkpointFilter(group, topic, partition),
		bson.D{
			{Key: "$setOnInsert", Value: bson.D{
				{Key: "consumer_group", Value: group},
				{Key: "topic", Value: topic},
				{Key: "partition", Value: partition},
				{Key: "created_at", Value: time.Now().UTC()},
			}},
			{Key: "$max", Value: bson.D{{Key: "next_offset", Value: nextOffset}}},
			{Key: "$currentDate", Value: bson.D{{Key: "updated_at", Value: true}}},
		},
		options.UpdateOne().SetUpsert(true),
	)
	if err != nil {
		return fmt.Errorf("advance projection checkpoint: %w", err)
	}
	return nil
}

func (s *MongoCheckpointStore) LoadAll(
	ctx context.Context,
	group string,
	partitions []TopicPartition,
) (map[TopicPartition]CheckpointState, error) {
	result := make(map[TopicPartition]CheckpointState, len(partitions))
	for _, tp := range partitions {
		checkpoint, found, err := s.Load(ctx, group, tp.Topic, tp.Partition)
		if err != nil {
			return nil, err
		}
		if found {
			result[tp] = checkpoint
		}
	}
	return result, nil
}

func snapshotMarkerCheckpointUpdate(
	group, topic string,
	partition int,
	nextOffset int64,
	marker SnapshotMarkerReceipt,
) mongo.Pipeline {
	existingNextOffset := bson.D{{
		Key: "$ifNull",
		Value: bson.A{
			"$next_offset",
			int64(-1),
		},
	}}
	existingMarkerOffset := bson.D{{
		Key: "$ifNull",
		Value: bson.A{
			"$snapshot_completed_offset",
			int64(-1),
		},
	}}
	isLatestMarker := bson.D{{
		Key: "$gte",
		Value: bson.A{
			marker.Offset,
			existingMarkerOffset,
		},
	}}

	return mongo.Pipeline{
		bson.D{{
			Key: "$set",
			Value: bson.D{
				{Key: "consumer_group", Value: group},
				{Key: "topic", Value: topic},
				{Key: "partition", Value: partition},
				{Key: "created_at", Value: bson.D{{
					Key:   "$ifNull",
					Value: bson.A{"$created_at", "$$NOW"},
				}}},
			},
		}},
		bson.D{{
			Key: "$set",
			Value: bson.D{
				{Key: "next_offset", Value: bson.D{{
					Key:   "$max",
					Value: bson.A{existingNextOffset, nextOffset},
				}}},
				{Key: "snapshot_id", Value: bson.D{{
					Key:   "$cond",
					Value: bson.A{isLatestMarker, marker.SnapshotID, "$snapshot_id"},
				}}},
				{Key: "snapshot_source", Value: bson.D{{
					Key:   "$cond",
					Value: bson.A{isLatestMarker, marker.Source, "$snapshot_source"},
				}}},
				{Key: "snapshot_occurred_at", Value: bson.D{{
					Key:   "$cond",
					Value: bson.A{isLatestMarker, marker.OccurredAt, "$snapshot_occurred_at"},
				}}},
				{Key: "snapshot_completed_offset", Value: bson.D{{
					Key:   "$max",
					Value: bson.A{existingMarkerOffset, marker.Offset},
				}}},
				{Key: "updated_at", Value: "$$NOW"},
			},
		}},
	}
}

func (s *MongoCheckpointStore) Quarantine(ctx context.Context, deadLetter DeadLetter) error {
	deadLetter.CreatedAt = time.Now().UTC()
	_, err := s.deadLetters.UpdateOne(
		ctx,
		bson.D{
			{Key: "consumer_group", Value: deadLetter.ConsumerGroup},
			{Key: "topic", Value: deadLetter.Topic},
			{Key: "partition", Value: deadLetter.Partition},
			{Key: "offset", Value: deadLetter.Offset},
		},
		bson.D{{Key: "$setOnInsert", Value: deadLetter}},
		options.UpdateOne().SetUpsert(true),
	)
	if err != nil {
		return fmt.Errorf("quarantine invalid projection event: %w", err)
	}
	return nil
}

func checkpointFilter(group, topic string, partition int) bson.D {
	return bson.D{
		{Key: "consumer_group", Value: group},
		{Key: "topic", Value: topic},
		{Key: "partition", Value: partition},
	}
}

func barrierComplete(ranges map[TopicPartition]OffsetRange, checkpoints map[TopicPartition]CheckpointState) bool {
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
