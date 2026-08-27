package mysql

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/cowork/cowork-notification/internal/domain/projection"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type channelNotificationPreference struct {
	AccountID           int64     `gorm:"column:account_id;primaryKey"`
	ChannelID           int64     `gorm:"column:channel_id;primaryKey"`
	NotificationEnabled bool      `gorm:"column:notification_enabled"`
	SourceUpdatedAt     time.Time `gorm:"column:source_updated_at"`
}

func (channelNotificationPreference) TableName() string {
	return "tb_channel_notification_preferences"
}

type userProfileProjection struct {
	UserID          int64     `gorm:"column:user_id;primaryKey"`
	DisplayName     string    `gorm:"column:display_name"`
	Deleted         bool      `gorm:"column:deleted"`
	SourceUpdatedAt time.Time `gorm:"column:source_updated_at"`
}

func (userProfileProjection) TableName() string {
	return "tb_user_profile_projections"
}

type teamProfileProjection struct {
	TeamID          int64     `gorm:"column:team_id;primaryKey"`
	TeamName        string    `gorm:"column:team_name"`
	Deleted         bool      `gorm:"column:deleted"`
	SourceUpdatedAt time.Time `gorm:"column:source_updated_at"`
}

func (teamProfileProjection) TableName() string {
	return "tb_team_profile_projections"
}

type ProjectionRepository struct {
	db *gorm.DB
}

type projectionCheckpoint struct {
	ConsumerGroup           string  `gorm:"column:consumer_group;primaryKey"`
	Topic                   string  `gorm:"column:topic_name;primaryKey"`
	Partition               int     `gorm:"column:partition_id;primaryKey"`
	TopicID                 string  `gorm:"column:topic_id"`
	NextOffset              int64   `gorm:"column:next_offset"`
	SnapshotCompletedOffset *int64  `gorm:"column:snapshot_completed_offset"`
	SnapshotID              *string `gorm:"column:snapshot_id"`
	InvalidRecordOffset     *int64  `gorm:"column:invalid_record_offset"`
	LastSnapshotID          *string `gorm:"column:last_snapshot_id"`
	RecoverySnapshotID      *string `gorm:"column:recovery_snapshot_id"`
}

func (projectionCheckpoint) TableName() string {
	return "tb_projection_checkpoints"
}

func NewProjectionRepository(db *gorm.DB) *ProjectionRepository {
	return &ProjectionRepository{db: db}
}

func (r *ProjectionRepository) ApplyWithCheckpoint(
	ctx context.Context,
	checkpoint projection.Checkpoint,
	apply func(projection.Store) error,
) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if _, err := prepareCheckpointGeneration(tx, checkpoint); err != nil {
			return err
		}
		txRepository := NewProjectionRepository(tx)
		if err := apply(txRepository); err != nil {
			return err
		}
		return advanceCheckpoint(tx, checkpoint)
	})
}

func (r *ProjectionRepository) QuarantineWithCheckpoint(
	ctx context.Context,
	record projection.InvalidRecord,
) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		recordOffset := record.Checkpoint.NextOffset - 1
		stored, err := prepareCheckpointGenerationAt(tx, record.Checkpoint, recordOffset)
		if err != nil {
			return err
		}
		if invalidRecordIsStale(stored.NextOffset, recordOffset) {
			return nil
		}
		if err := tx.Exec(
			`INSERT INTO tb_projection_dead_letters
				(consumer_group, topic_name, partition_id, topic_id, message_offset, event_key, payload, reason)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS incoming
			 ON DUPLICATE KEY UPDATE
				topic_id = incoming.topic_id,
				event_key = incoming.event_key,
				payload = incoming.payload,
				reason = incoming.reason`,
			record.Checkpoint.ConsumerGroup,
			record.Checkpoint.Topic,
			record.Checkpoint.Partition,
			record.Checkpoint.TopicID,
			recordOffset,
			record.Key,
			record.Payload,
			record.Reason,
		).Error; err != nil {
			return err
		}
		if !record.LatchStateGap {
			return advanceCheckpoint(tx, record.Checkpoint)
		}

		latch := latchInvalidRecord(stored.recoveryLatch(), recordOffset)
		return tx.Exec(
			`UPDATE tb_projection_checkpoints
			 SET next_offset = GREATEST(next_offset, ?),
				invalid_record_offset = ?,
				recovery_snapshot_id = ?
			 WHERE consumer_group = ? AND topic_name = ? AND partition_id = ? AND topic_id = ?`,
			record.Checkpoint.NextOffset,
			latch.InvalidRecordOffset,
			latch.RecoverySnapshotID,
			record.Checkpoint.ConsumerGroup,
			record.Checkpoint.Topic,
			record.Checkpoint.Partition,
			record.Checkpoint.TopicID,
		).Error
	})
}

func (r *ProjectionRepository) RecordSnapshotMarkerWithCheckpoint(
	ctx context.Context,
	checkpoint projection.Checkpoint,
	marker projection.SnapshotMarkerReceipt,
) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		stored, err := prepareCheckpointGeneration(tx, checkpoint)
		if err != nil {
			return err
		}
		latch := recordRecoverySnapshot(stored.recoveryLatch(), marker.Offset, marker.SnapshotID)
		return tx.Exec(
			`UPDATE tb_projection_checkpoints
			 SET next_offset = GREATEST(next_offset, ?),
				invalid_record_offset = ?,
				last_snapshot_id = ?,
				recovery_snapshot_id = ?,
				snapshot_id = IF(
					? >= COALESCE(snapshot_completed_offset, -1),
					?,
					snapshot_id
				),
				snapshot_source = IF(
					? >= COALESCE(snapshot_completed_offset, -1),
					?,
					snapshot_source
				),
				snapshot_occurred_at = IF(
					? >= COALESCE(snapshot_completed_offset, -1),
					?,
					snapshot_occurred_at
				),
				snapshot_completed_offset = GREATEST(
					COALESCE(snapshot_completed_offset, -1),
					?
				)
			 WHERE consumer_group = ? AND topic_name = ? AND partition_id = ? AND topic_id = ?`,
			checkpoint.NextOffset,
			latch.InvalidRecordOffset,
			latch.LastSnapshotID,
			latch.RecoverySnapshotID,
			marker.Offset,
			marker.SnapshotID,
			marker.Offset,
			marker.Source,
			marker.Offset,
			marker.OccurredAt,
			marker.Offset,
			checkpoint.ConsumerGroup,
			checkpoint.Topic,
			checkpoint.Partition,
			checkpoint.TopicID,
		).Error
	})
}

func (r *ProjectionRepository) LoadCheckpoint(
	ctx context.Context,
	group, topic string,
	partition int,
) (projection.CheckpointState, bool, error) {
	var row projectionCheckpoint
	err := r.db.WithContext(ctx).
		Where("consumer_group = ? AND topic_name = ? AND partition_id = ?", group, topic, partition).
		First(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return projection.CheckpointState{}, false, nil
	}
	if err != nil {
		return projection.CheckpointState{}, false, err
	}
	return row.toState(), true, nil
}

func (r *ProjectionRepository) AdvanceCheckpoint(ctx context.Context, checkpoint projection.Checkpoint) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if _, err := prepareCheckpointGeneration(tx, checkpoint); err != nil {
			return err
		}
		return advanceCheckpoint(tx, checkpoint)
	})
}

func (r *ProjectionRepository) LoadCheckpoints(
	ctx context.Context,
	group string,
	partitions []projection.TopicPartition,
) (map[projection.TopicPartition]projection.CheckpointState, error) {
	result := make(map[projection.TopicPartition]projection.CheckpointState, len(partitions))
	for _, partition := range partitions {
		var row projectionCheckpoint
		err := r.db.WithContext(ctx).
			Where(
				"consumer_group = ? AND topic_name = ? AND partition_id = ?",
				group,
				partition.Topic,
				partition.Partition,
			).
			First(&row).Error
		if errors.Is(err, gorm.ErrRecordNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		result[partition] = projection.CheckpointState{
			TopicID:                 row.TopicID,
			NextOffset:              row.NextOffset,
			SnapshotCompletedOffset: row.SnapshotCompletedOffset,
			InvalidRecordOffset:     row.InvalidRecordOffset,
		}
	}
	return result, nil
}

func advanceCheckpoint(db *gorm.DB, checkpoint projection.Checkpoint) error {
	return db.Exec(
		`UPDATE tb_projection_checkpoints
		 SET next_offset = GREATEST(next_offset, ?)
		 WHERE consumer_group = ? AND topic_name = ? AND partition_id = ? AND topic_id = ?`,
		checkpoint.NextOffset,
		checkpoint.ConsumerGroup,
		checkpoint.Topic,
		checkpoint.Partition,
		checkpoint.TopicID,
	).Error
}

func prepareCheckpointGeneration(db *gorm.DB, checkpoint projection.Checkpoint) (projectionCheckpoint, error) {
	return prepareCheckpointGenerationAt(db, checkpoint, checkpoint.NextOffset)
}

func prepareCheckpointGenerationAt(
	db *gorm.DB,
	checkpoint projection.Checkpoint,
	initialNextOffset int64,
) (projectionCheckpoint, error) {
	topicID, err := canonicalTopicID(checkpoint.TopicID)
	if err != nil {
		return projectionCheckpoint{}, err
	}
	if err := db.Exec(
		`INSERT INTO tb_projection_checkpoints
			(consumer_group, topic_name, partition_id, topic_id, next_offset)
		 VALUES (?, ?, ?, ?, ?) AS incoming
		 ON DUPLICATE KEY UPDATE next_offset = tb_projection_checkpoints.next_offset`,
		checkpoint.ConsumerGroup,
		checkpoint.Topic,
		checkpoint.Partition,
		topicID,
		initialNextOffset,
	).Error; err != nil {
		return projectionCheckpoint{}, err
	}

	var stored projectionCheckpoint
	err = db.Clauses(clause.Locking{Strength: "UPDATE"}).
		Where(
			"consumer_group = ? AND topic_name = ? AND partition_id = ?",
			checkpoint.ConsumerGroup,
			checkpoint.Topic,
			checkpoint.Partition,
		).
		Take(&stored).Error
	if err != nil {
		return projectionCheckpoint{}, err
	}
	if stored.TopicID != topicID {
		return projectionCheckpoint{}, fmt.Errorf(
			"%w: topic=%s partition=%d checkpointTopicId=%q brokerTopicId=%q",
			projection.ErrTopicGenerationMismatch,
			checkpoint.Topic,
			checkpoint.Partition,
			stored.TopicID,
			topicID,
		)
	}
	return stored, nil
}

func canonicalTopicID(value string) (string, error) {
	topicID, err := uuid.Parse(value)
	if err != nil || topicID == uuid.Nil || value != topicID.String() {
		return "", fmt.Errorf(
			"%w: invalid brokerTopicId=%q",
			projection.ErrTopicGenerationMismatch,
			value,
		)
	}
	return topicID.String(), nil
}

func (row projectionCheckpoint) toState() projection.CheckpointState {
	return projection.CheckpointState{
		TopicID:                 row.TopicID,
		NextOffset:              row.NextOffset,
		SnapshotCompletedOffset: row.SnapshotCompletedOffset,
		InvalidRecordOffset:     row.InvalidRecordOffset,
	}
}

type recoveryLatch struct {
	InvalidRecordOffset *int64
	LastSnapshotID      *string
	RecoverySnapshotID  *string
}

func (row projectionCheckpoint) recoveryLatch() recoveryLatch {
	lastSnapshotID := row.LastSnapshotID
	if lastSnapshotID == nil && row.SnapshotID != nil {
		if parsed, err := uuid.Parse(*row.SnapshotID); err == nil && parsed != uuid.Nil {
			lastSnapshotID = stringPointer(parsed.String())
		}
	}
	return recoveryLatch{
		InvalidRecordOffset: row.InvalidRecordOffset,
		LastSnapshotID:      lastSnapshotID,
		RecoverySnapshotID:  row.RecoverySnapshotID,
	}
}

func latchInvalidRecord(state recoveryLatch, recordOffset int64) recoveryLatch {
	if state.InvalidRecordOffset == nil || recordOffset > *state.InvalidRecordOffset {
		state.InvalidRecordOffset = int64Pointer(recordOffset)
		state.RecoverySnapshotID = nil
	}
	return state
}

func invalidRecordIsStale(nextOffset, recordOffset int64) bool {
	return recordOffset < nextOffset
}

func recordRecoverySnapshot(state recoveryLatch, markerOffset int64, snapshotID string) recoveryLatch {
	if state.InvalidRecordOffset != nil && markerOffset > *state.InvalidRecordOffset &&
		!stringPointerEquals(state.LastSnapshotID, snapshotID) {
		switch {
		case state.RecoverySnapshotID == nil:
			state.RecoverySnapshotID = stringPointer(snapshotID)
		case *state.RecoverySnapshotID != snapshotID:
			state.InvalidRecordOffset = nil
			state.RecoverySnapshotID = nil
		}
	}
	state.LastSnapshotID = stringPointer(snapshotID)
	return state
}

func stringPointerEquals(value *string, expected string) bool {
	return value != nil && *value == expected
}

func int64Pointer(value int64) *int64 {
	return &value
}

func stringPointer(value string) *string {
	return &value
}

func (r *ProjectionRepository) UpsertChannelNotification(
	ctx context.Context,
	accountID, channelID int64,
	enabled bool,
	occurredAt time.Time,
) error {
	return r.db.WithContext(ctx).Exec(
		`INSERT INTO tb_channel_notification_preferences
			(account_id, channel_id, notification_enabled, source_updated_at)
		 VALUES (?, ?, ?, ?) AS incoming
		 ON DUPLICATE KEY UPDATE
			notification_enabled = IF(
				incoming.source_updated_at >= tb_channel_notification_preferences.source_updated_at,
				incoming.notification_enabled,
				tb_channel_notification_preferences.notification_enabled
			),
			source_updated_at = GREATEST(
				tb_channel_notification_preferences.source_updated_at,
				incoming.source_updated_at
			)`,
		accountID,
		channelID,
		enabled,
		occurredAt,
	).Error
}

func (r *ProjectionRepository) AreNotificationsEnabled(
	ctx context.Context,
	accountIDs []int64,
	channelID int64,
) (map[int64]bool, error) {
	if len(accountIDs) == 0 {
		return map[int64]bool{}, nil
	}
	var rows []channelNotificationPreference
	if err := r.db.WithContext(ctx).
		Where("channel_id = ? AND account_id IN ?", channelID, accountIDs).
		Find(&rows).Error; err != nil {
		return nil, err
	}
	result := make(map[int64]bool, len(rows))
	for _, row := range rows {
		result[row.AccountID] = row.NotificationEnabled
	}
	return result, nil
}

func (r *ProjectionRepository) UpsertUserProfile(
	ctx context.Context,
	userID int64,
	displayName string,
	occurredAt time.Time,
) error {
	return r.upsertUserProfile(ctx, userID, displayName, false, occurredAt)
}

func (r *ProjectionRepository) DeleteUserProfile(ctx context.Context, userID int64, occurredAt time.Time) error {
	return r.upsertUserProfile(ctx, userID, "", true, occurredAt)
}

func (r *ProjectionRepository) GetDisplayName(ctx context.Context, userID int64) (string, error) {
	var row userProfileProjection
	err := r.db.WithContext(ctx).Where("user_id = ? AND deleted = FALSE", userID).First(&row).Error
	return row.DisplayName, err
}

func (r *ProjectionRepository) UpsertTeamProfile(
	ctx context.Context,
	teamID int64,
	teamName string,
	occurredAt time.Time,
) error {
	return r.upsertTeamProfile(ctx, teamID, teamName, false, occurredAt)
}

func (r *ProjectionRepository) DeleteTeamProfile(ctx context.Context, teamID int64, occurredAt time.Time) error {
	return r.upsertTeamProfile(ctx, teamID, "", true, occurredAt)
}

func (r *ProjectionRepository) GetName(ctx context.Context, teamID int64) (string, error) {
	var row teamProfileProjection
	err := r.db.WithContext(ctx).Where("team_id = ? AND deleted = FALSE", teamID).First(&row).Error
	return row.TeamName, err
}

func (r *ProjectionRepository) upsertUserProfile(
	ctx context.Context,
	userID int64,
	displayName string,
	deleted bool,
	occurredAt time.Time,
) error {
	return r.db.WithContext(ctx).Exec(
		`INSERT INTO tb_user_profile_projections
			(user_id, display_name, deleted, source_updated_at)
		 VALUES (?, ?, ?, ?) AS incoming
		 ON DUPLICATE KEY UPDATE
			display_name = IF(
				incoming.source_updated_at > tb_user_profile_projections.source_updated_at OR
				(
					incoming.source_updated_at = tb_user_profile_projections.source_updated_at AND
					incoming.deleted >= tb_user_profile_projections.deleted
				),
				incoming.display_name,
				tb_user_profile_projections.display_name
			),
			deleted = IF(
				incoming.source_updated_at > tb_user_profile_projections.source_updated_at OR
				(
					incoming.source_updated_at = tb_user_profile_projections.source_updated_at AND
					incoming.deleted >= tb_user_profile_projections.deleted
				),
				incoming.deleted,
				tb_user_profile_projections.deleted
			),
			source_updated_at = GREATEST(
				tb_user_profile_projections.source_updated_at,
				incoming.source_updated_at
			)`,
		userID,
		displayName,
		deleted,
		occurredAt,
	).Error
}

func (r *ProjectionRepository) upsertTeamProfile(
	ctx context.Context,
	teamID int64,
	teamName string,
	deleted bool,
	occurredAt time.Time,
) error {
	return r.db.WithContext(ctx).Exec(
		`INSERT INTO tb_team_profile_projections
			(team_id, team_name, deleted, source_updated_at)
		 VALUES (?, ?, ?, ?) AS incoming
		 ON DUPLICATE KEY UPDATE
			team_name = IF(
				incoming.source_updated_at > tb_team_profile_projections.source_updated_at OR
				(
					incoming.source_updated_at = tb_team_profile_projections.source_updated_at AND
					incoming.deleted >= tb_team_profile_projections.deleted
				),
				incoming.team_name,
				tb_team_profile_projections.team_name
			),
			deleted = IF(
				incoming.source_updated_at > tb_team_profile_projections.source_updated_at OR
				(
					incoming.source_updated_at = tb_team_profile_projections.source_updated_at AND
					incoming.deleted >= tb_team_profile_projections.deleted
				),
				incoming.deleted,
				tb_team_profile_projections.deleted
			),
			source_updated_at = GREATEST(
				tb_team_profile_projections.source_updated_at,
				incoming.source_updated_at
			)`,
		teamID,
		teamName,
		deleted,
		occurredAt,
	).Error
}
