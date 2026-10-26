package sqlite

import (
	"context"
	"time"

	"gorm.io/gorm"
)

type eventInboxRow struct {
	EventID     string    `gorm:"column:event_id;primaryKey"`
	CompletedAt time.Time `gorm:"column:completed_at"`
}

func (eventInboxRow) TableName() string { return "tb_notification_event_inbox" }

// EventInboxRepository records notification trigger events whose recipient fan-out
// already completed, so a Kafka redelivery is not turned into a new notification.
// Per-installation FCM outcomes stay in DeliveryRepository's ledger.
type EventInboxRepository struct {
	db *gorm.DB
}

func NewEventInboxRepository(db *gorm.DB) *EventInboxRepository {
	return &EventInboxRepository{db: db}
}

func (r *EventInboxRepository) IsCompleted(ctx context.Context, eventID string) (bool, error) {
	var count int64
	err := r.db.WithContext(ctx).Model(&eventInboxRow{}).Where("event_id = ?", eventID).Count(&count).Error
	return count > 0, err
}

func (r *EventInboxRepository) MarkCompleted(ctx context.Context, eventID string) error {
	return r.db.WithContext(ctx).Exec(
		`INSERT INTO tb_notification_event_inbox (event_id, completed_at) VALUES (?, ?)
		 ON CONFLICT (event_id) DO NOTHING`,
		eventID, time.Now().UTC(),
	).Error
}

// PurgeCompleted deletes inbox rows completed before olderThan in purgeBatchSize
// chunks, following DeliveryRepository.PurgeTerminal.
func (r *EventInboxRepository) PurgeCompleted(ctx context.Context, olderThan time.Time) (int64, error) {
	var total int64
	for {
		batch := r.db.WithContext(ctx).Model(&eventInboxRow{}).
			Select("event_id").
			Where("completed_at < ?", olderThan.UTC()).
			Limit(purgeBatchSize)
		result := r.db.WithContext(ctx).
			Where("event_id IN (?)", batch).
			Delete(&eventInboxRow{})
		if result.Error != nil {
			return total, result.Error
		}
		total += result.RowsAffected
		if result.RowsAffected < purgeBatchSize {
			return total, nil
		}
		if err := ctx.Err(); err != nil {
			return total, err
		}
	}
}
