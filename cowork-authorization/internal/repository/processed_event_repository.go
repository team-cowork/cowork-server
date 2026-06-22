package repository

import (
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

// Exists reports whether the event id has already been processed.
func (r *ProcessedEventRepository) Exists(eventID string) (bool, error) {
	var count int64
	if err := r.db.Model(&domain.ProcessedEvent{}).Where("event_id = ?", eventID).Count(&count).Error; err != nil {
		return false, err
	}
	return count > 0, nil
}

// MarkProcessed records the event id and reports whether it was newly inserted.
// Returns false when the event id already exists (duplicate delivery).
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
