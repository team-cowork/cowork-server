package repository

import (
	"time"

	"github.com/cowork/authorization/internal/domain"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type ProcessedEventRepository struct {
	db *gorm.DB
}

func NewProcessedEventRepository(db *gorm.DB) *ProcessedEventRepository {
	return &ProcessedEventRepository{db: db}
}

func (r *ProcessedEventRepository) Exists(eventID string) (bool, error) {
	var count int64
	if err := r.db.Model(&domain.ProcessedEvent{}).
		Where("event_id = ?", eventID).
		Count(&count).Error; err != nil {
		return false, err
	}
	return count > 0, nil
}

func (r *ProcessedEventRepository) MarkProcessed(eventID, eventType string) (bool, error) {
	result := r.db.Clauses(clause.OnConflict{DoNothing: true}).Create(&domain.ProcessedEvent{
		EventID:   eventID,
		EventType: eventType,
	})
	if result.Error != nil {
		return false, result.Error
	}
	return result.RowsAffected > 0, nil
}

// DeleteOlderThan removes processed-event records older than the retention window.
// DataGSM retries complete within hours/days, so old ids no longer need keeping.
func (r *ProcessedEventRepository) DeleteOlderThan(retention time.Duration) error {
	cutoff := time.Now().Add(-retention)
	return r.db.Where("created_at < ?", cutoff).Delete(&domain.ProcessedEvent{}).Error
}
