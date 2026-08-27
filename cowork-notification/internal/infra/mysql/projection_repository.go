package mysql

import (
	"context"
	"errors"
	"time"

	"github.com/cowork/cowork-notification/internal/domain/projection"
	"gorm.io/gorm"
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
	ConsumerGroup           string `gorm:"column:consumer_group;primaryKey"`
	Topic                   string `gorm:"column:topic_name;primaryKey"`
	Partition               int    `gorm:"column:partition_id;primaryKey"`
	NextOffset              int64  `gorm:"column:next_offset"`
	SnapshotCompletedOffset *int64 `gorm:"column:snapshot_completed_offset"`
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
		if err := tx.Exec(
			`INSERT INTO tb_projection_dead_letters
				(consumer_group, topic_name, partition_id, message_offset, event_key, payload, reason)
			 VALUES (?, ?, ?, ?, ?, ?, ?) AS incoming
			 ON DUPLICATE KEY UPDATE message_offset = incoming.message_offset`,
			record.Checkpoint.ConsumerGroup,
			record.Checkpoint.Topic,
			record.Checkpoint.Partition,
			record.Checkpoint.NextOffset-1,
			record.Key,
			record.Payload,
			record.Reason,
		).Error; err != nil {
			return err
		}
		return advanceCheckpoint(tx, record.Checkpoint)
	})
}

func (r *ProjectionRepository) RecordSnapshotMarkerWithCheckpoint(
	ctx context.Context,
	checkpoint projection.Checkpoint,
	marker projection.SnapshotMarkerReceipt,
) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		return tx.Exec(
			`INSERT INTO tb_projection_checkpoints
				(consumer_group, topic_name, partition_id, next_offset,
				 snapshot_completed_offset, snapshot_id, snapshot_source, snapshot_occurred_at)
			 VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS incoming
			 ON DUPLICATE KEY UPDATE
				next_offset = GREATEST(tb_projection_checkpoints.next_offset, incoming.next_offset),
				snapshot_id = IF(
					incoming.snapshot_completed_offset >= COALESCE(tb_projection_checkpoints.snapshot_completed_offset, -1),
					incoming.snapshot_id,
					tb_projection_checkpoints.snapshot_id
				),
				snapshot_source = IF(
					incoming.snapshot_completed_offset >= COALESCE(tb_projection_checkpoints.snapshot_completed_offset, -1),
					incoming.snapshot_source,
					tb_projection_checkpoints.snapshot_source
				),
				snapshot_occurred_at = IF(
					incoming.snapshot_completed_offset >= COALESCE(tb_projection_checkpoints.snapshot_completed_offset, -1),
					incoming.snapshot_occurred_at,
					tb_projection_checkpoints.snapshot_occurred_at
				),
				snapshot_completed_offset = GREATEST(
					COALESCE(tb_projection_checkpoints.snapshot_completed_offset, -1),
					incoming.snapshot_completed_offset
				)`,
			checkpoint.ConsumerGroup,
			checkpoint.Topic,
			checkpoint.Partition,
			checkpoint.NextOffset,
			marker.Offset,
			marker.SnapshotID,
			marker.Source,
			marker.OccurredAt,
		).Error
	})
}

func (r *ProjectionRepository) LoadCheckpoint(
	ctx context.Context,
	group, topic string,
	partition int,
) (int64, bool, error) {
	var row projectionCheckpoint
	err := r.db.WithContext(ctx).
		Where("consumer_group = ? AND topic_name = ? AND partition_id = ?", group, topic, partition).
		First(&row).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, err
	}
	return row.NextOffset, true, nil
}

func (r *ProjectionRepository) AdvanceCheckpoint(ctx context.Context, checkpoint projection.Checkpoint) error {
	return advanceCheckpoint(r.db.WithContext(ctx), checkpoint)
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
			NextOffset:              row.NextOffset,
			SnapshotCompletedOffset: row.SnapshotCompletedOffset,
		}
	}
	return result, nil
}

func advanceCheckpoint(db *gorm.DB, checkpoint projection.Checkpoint) error {
	return db.Exec(
		`INSERT INTO tb_projection_checkpoints
			(consumer_group, topic_name, partition_id, next_offset)
		 VALUES (?, ?, ?, ?) AS incoming
		 ON DUPLICATE KEY UPDATE
			next_offset = GREATEST(tb_projection_checkpoints.next_offset, incoming.next_offset)`,
		checkpoint.ConsumerGroup,
		checkpoint.Topic,
		checkpoint.Partition,
		checkpoint.NextOffset,
	).Error
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
