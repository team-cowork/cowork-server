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
